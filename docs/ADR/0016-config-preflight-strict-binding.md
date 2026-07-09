# 0016 — Надёжность конфигурации: единый preflight + строгий binding

## Статус

**Принято 2026-07-08, не реализовано.** Закрывает блок `CFG` из
[KNOWN-ISSUES](../KNOWN-ISSUES.md) (CFG-1, CFG-2, CFG-4; CFG-3 уже закрыт
удалением кода при β-collapse) как один класс проблем, а не точечными
фиксами. Инвентарь проблем, верификация по коду, механика binding-трубы и
порядок реализации — в
[worknote/config-hardening.md](../worknote/config-hardening.md).

**Реализовано 2026-07-09** в диапазоне коммитов `b0e5157..8575527`
(`b0e5157`, `9bb9e63`, `ec14c8d`, `da27b24`, `30f1884`, `3d45cdd`,
`e01e2fa`, `8575527`). Shipped-модель: `configurationPropertiesValidator`
для semantic collect-all, reflection-shape unknown-key preflight вместо
`ignoreUnknownFields=false`, legacy `FailureAnalyzer` с кодами `CONFIG.*`,
typed selectors, sealed `IdStart` + converter, registry-backed eager preflight.
CFG-1, CFG-2, CFG-3 и CFG-4 закрыты в [KNOWN-ISSUES](../KNOWN-ISSUES.md).

## Контекст

`ioc.*` — не «настройки», а DSL с типизированными значениями и символьными
ссылками между секциями (`artifact-identity → sink`, `export → sink`,
`classify → predicate registry`, `columns.from → provider registry`).
Валидация этого DSL размазана по трём идиомам (JSR-380 аннотации,
compact-constructor throws, registry-throws при создании бинов) и срабатывает
в четыре разных момента: биндинг, старт контекста, первая запись — или
никогда.

Худший режим отказа — CFG-2: опечатка в `artifact-identity` не проявляется на
старте. Ссылка на несуществующий артефакт — тихий no-op плюс мусорная
durable-запись в identity store; артефакт без identity-определения падает не
на старте, а на первой записи («Cannot resolve row_key», в daemon — файл в
retry/failed без намёка на конфиг); опечатка в одной из composite
`key-columns` вовсе не падает — колонка молча кодируется `null`, неверный
`row_key` пишется в БД навсегда, а исправление опечатки означает identity
drift → HALT → epoch-миграцию. Ошибка конфига проявляется как ошибка данных,
в другое время и в другом месте.

Относительно неизвестных ключей binding работает в relaxed-режиме: опечатка в
имени ключа молча оставляет дефолт. Против уже выведенных ключей накопились
два рукописных tombstone (`smb.read-timeout`, record `Lookup` целиком) —
приём рабочий, но каждый вывод ключа требует нового ручного стража, а
поля-призраки живут в модели вечно.

## Решения

### 1. Единый startup preflight для `ioc.*`

`ioc.*` проходит единый операторский startup preflight до сборки runtime graph.
Целевой отчёт покрывает unknown/deprecated keys, binding/conversion failures,
JSR-380, локальные инварианты, закрытые словари и config→config ссылочную
целостность. Никакая ошибка `ioc.*` не доживает до первой записи, daemon file
handling или lazy-активации экспортно-синхронизационного графа.

Семантическая часть preflight'а живёт в бине
`org.springframework.validation.Validator` с именем
`configurationPropertiesValidator` (static `@Bean`) — штатном Spring-Boot-хуке,
выполняемом при биндинге тем же `ValidationBindHandler`, что и JSR-380. Он
собирает нарушения на уже связанной форме конфигурации пачкой, с
property-путями (`ioc.artifact-identity.artifacts[2].key-columns[1]`) вместо
throw-first.

Техническое ограничение: malformed YAML, conversion failures, enum binding
failures и unknown-key failures могут остановить binding до запуска части
семантических проверок, потому что объект конфигурации физически не построен.
Это считается shape/binding failure. Для всех ошибок на построенной форме
конфигурации действует collect-all контракт.

**Compact-конструкторы сужаются до нормализации/дефолтов и не бросают по
операторским ошибкам.** Граница жёсткая: конструктор, бросивший во время
биндинга, не даёт объекту сконструироваться — Validator не запускается, и
одна ошибка маскирует весь список. JSR-380 аннотации остаются (декларативная
форма); registry-проверки (classify-предикаты, `columns.from`, transforms)
остаются на создании бинов — им нужны реестры; соответствующие бины обязаны
быть eager.

Внутренняя структура preflight'а модульная: один Spring-facing фасад
(`IocConfigPreflight` / `IocConfigValidator`) формирует единый отчёт, но
проверки разделены на маленькие правила по ответственности (shape,
reference-integrity, literals/value-types, policy). Это сохраняет единый
жизненный цикл без превращения валидатора в god class.

### 2. Ссылочная целостность конфига (закрывает CFG-2)

Проверки в preflight'е, все config→config:

1. `artifact-identity.artifacts[].name` ссылается на существующий
   `sink.artifacts[].name`. **`enabled` не требуется**: identity-определение
   безвредно и обязано переживать временное отключение артефакта (в отличие
   от export-профилей, где disabled = unknown — там доставка).
2. Каждый **enabled** sink-артефакт имеет identity-определение — падение
   первой записи становится ошибкой старта.
3. `key-columns[]` ⊆ колонок соответствующего артефакта — закрывает тихий
   `null` в composite-ключе.
4. Имена артефактов и identity-def'ов уникальны; имена колонок уникальны внутри
   каждого sink-артефакта.
5. `id.start` и `id.strategy` согласованы с public `id`-колонкой: явный
   numeric `id.start` не допускается для артефакта без public id.
6. Литералы закрытых словарей и `id.start` (см. Решение 5).

### 3. Строгий binding: неизвестный ключ = ошибка старта (закрывает CFG-4)

Первый кандидат — `@ConfigurationProperties(ignoreUnknownFields = false)`
(под капотом `NoUnboundElementsBindHandler`; systemEnvironment/systemProperties
отфильтрованы `UnboundElementsSourceFilter`, ложных отказов от env быть не
должно). Связка constructor binding (records) + Boot 3.3.5 **проверяется
экспериментальным тестом до фиксации**. Если понадобится кастомизация, fallback
идёт через официальный binding-шов (`ConfigurationPropertiesBindHandlerAdvisor`
или metadata-based preflight), но строго ограничивается root-моделью
`IocProperties`/prefix `ioc`, чтобы не зацепить adapter-local binding вроде
`IngestAdapterProperties(prefix = "ioc.ingestion")`. В любом случае
подключается `spring-boot-configuration-processor` в `bootstrap/ioc-app`
(машиночитаемые метаданные ключей + автодополнение операторского YAML в IDE).

**Implementation note 2026-07-09.** Эксперимент с
`ignoreUnknownFields=false` (B1) был проведён и отвергнут: на текущей
record/list-shape модели Boot даёт ложные unbound failures на частичных
overlay/list overrides, а CLI/unknown-channel репортится иначе, чем нужно
операторскому контракту. Выбран B2-вариант без YAML-препарсера:
`IocUnknownConfigurationPreflight` сверяет фактические `ioc.*` property names
с reflection-shape `IocProperties`, ограничен root prefix `ioc` и не трогает
adapter-local binding. Legacy-подсказки остались в `FailureAnalyzer`.

### 4. Tombstones удаляются из модели; подсказки живут в FailureAnalyzer

Поля-призраки (`Smb.readTimeout`, record `Lookup`) удаляются из
`IocProperties` полностью — неизвестный ключ теперь отбивает строгий binding.
Адресные миграционные подсказки («`ioc.lookup.deduplicate` moved to
`ioc.pipeline.deduplicate`», «`read-timeout` → use `request-timeout`»)
переезжают в кастомный `FailureAnalyzer` (Description/Action) с маленькой
картой legacy-ключ → подсказка. Существующие tombstone-тесты заменяются
analyzer-тестами с тем же контрактом сообщений.

**FailureAnalyzer не связывается с `platform-diagnostics`:** разные жизненные
циклы (`Diagnostic` — событие работающего пайплайна с ECS-потребителем;
analyzer — терминальное объяснение оператору при смерти процесса на старте),
и технически analyzer живёт вне собранного контекста (`spring.factories`) —
`DiagnosticSink` в момент падения биндинга не существует. Допустимое
заимствование — конвенция стабильных кодов в тексте сообщений.

### 5. Типизация значений (закрывает CFG-1)

Все закрытые словари `ioc.*` уходят из stringly-typed модели в enum/value types:
`key-mode`, `id.strategy`, `trigger.type`, `output-mode`, `ledger.type`,
`runtime.mode`, `observability.mode`, `engine`, `storage.*.type`,
`sync.endpoint.transport`, `maintenance.retention.targets[].action` и другие
однотипные selectors. Все потребители перестают сравнивать строки
(`equalsIgnoreCase`) и работают через типы.

Если selector участвует в ранних Spring conditions, `EnvironmentPostProcessor`
или других местах, где `IocProperties` ещё не связан, рядом вводится единый
parser/normalizer. Ранний consumer и `IocProperties` используют одну грамматику,
а не две параллельные строковые интерпретации.

**`id.start`** — tagged union (`"auto" | long`) — получает единый parser/value
contract, используемый и preflight'ом, и `AppConfig.startOf`. Мёртвый
`catch (NumberFormatException)` удаляется: после preflight недопустимый литерал
не может попасть в runtime path. Sealed `IdStart` +
`@ConfigurationPropertiesBinding`-Converter допустим как финальная форма, но
не является обязательным ради одного union-поля, если единый parser уже держит
инвариант.

### 6. Конвенция сообщений об ошибках конфига

Сообщение говорит языком оператора: ключ → значение → как исправить. Без
внутреннего жаргона и внутренней нумерации этапов (урок CFG-3, «stage 11»).

## Следствия

- CFG-1, CFG-2, CFG-4 закрываются; CFG-3 закрывается ссылкой на storage
  collapse; реестр KNOWN-ISSUES обновляется вместе с реализацией.
- Ошибка конфига не переживает старт: то, что сегодня падает на первой
  записи или молчит, становится startup failure с операторским отчётом.
- `IocProperties` худеет: конструкторы — только дефолты, tombstone-поля
  удалены; появляются `IocConfigPreflight` / `IocConfigValidator` (bootstrap) и
  `FailureAnalyzer`.
- Негативные тесты идут через настоящий Boot binding/startup path
  (`ApplicationContextRunner`/минимальный `SpringApplication`), а не только
  через ручной `Binder`: проверяются validator, strict binding,
  FailureAnalyzer, yaml-опечатка, env, CLI и overlay.

## Отклонённые варианты

- **Расширять compact-constructor идиому** — throw-first чинит по одной
  ошибке за прогон, без property-путей; конструктор уже перегружен.
- **Связка FailureAnalyzer ↔ DiagnosticSink** — категориально разные каналы;
  бина-приёмника в момент boot-фейла не существует; у упавшего на старте
  процесса нет ECS-потребителя.
- **Две грамматики для одного selector'а** — ранние consumers и
  `IocProperties` не должны независимо парсить одни и те же строки. Если
  selector нужен до биндинга, выносим общий parser/normalizer.
- **Собственный YAML-препарсер для строгости** — вне Binder'а невозможно
  знать, какие ключи потреблены (relaxed binding, вложенные maps); строгость
  должна жить в binding-фазе.

## Открытые вопросы

1. Экспериментальная проверка `ignoreUnknownFields = false` на
   records/Boot 3.3.5 (Решение 3); при отказе — binding advisor или metadata
   preflight, ограниченный `IocProperties`/prefix `ioc`.
2. Стабильные коды в сообщениях (`CONFIG.*`) — решить при реализации
   (Решение 4, заимствование конвенции без DiagnosticSink).
3. Финальная форма `id.start`: sealed value object или единый parser/value
   contract без converter'а. Обязательный инвариант один: preflight и runtime
   используют одну грамматику.

**Закрыто 2026-07-09.**

1. B1 отвергнут, выбран reflection-shape preflight для unknown keys
   (`IocUnknownConfigurationPreflight`).
2. Стабильные коды `CONFIG.*` используются в startup/analyzer сообщениях без
   привязки к `DiagnosticSink`.
3. `id.start` реализован как sealed `IdStart` (`auto`/`explicit(long)`) с
   `@ConfigurationPropertiesBinding` converter и общей нормализованной
   грамматикой для binding/preflight/runtime.

## План реализации по срезам

План ниже — execution checklist для имплементации. Целевая модель цельная:
единый startup preflight, strict binding, typed selectors, единая грамматика
ранних и поздних consumers, tombstones вне `IocProperties`. Коммитить лучше логическими группами:
preflight lifecycle отдельно, strict binding/analyzer отдельно, value typing
отдельно, docs/tracking отдельно. Каждый срез сохраняет инварианты архитектуры:
вся Spring/Boot-специфика остаётся в `bootstrap/ioc-app`, domain/application
не знают о конфиге, adapter-local binding не становится источником правил для
root DSL.

### Срез 1 — Preflight lifecycle и миграция существующих constructor-проверок (выполнен: `b0e5157`)

**Цель.** Ввести единый Spring-facing preflight-шов и убрать throw-first
операторские ошибки из compact-конструкторов `IocProperties`, не меняя пока
новые CFG-2 правила.

**Изменения кода:**

- `bootstrap/ioc-app/.../IocConfigPreflight.java`
  - новый package-private/public финальный класс, реализующий
    `org.springframework.validation.Validator`;
  - `supports(...)` возвращает `true` только для `IocProperties`;
  - `validate(...)` делегирует маленьким rule-методам/классам:
    `shape`, `localPolicy`, `syncReferences`, `legacyRemovedKeys` и т.п.;
  - ошибки добавляются через `Errors.rejectValue(...)` с property-path,
    rejected value и операторским сообщением; сообщения строятся по конвенции
    `ключ → значение → как исправить`;
  - не валидировать adapter-local `IngestAdapterProperties`.
- `bootstrap/ioc-app/.../ConfigPreflightConfiguration.java` или `AppConfig.java`
  - добавить static bean с именем `configurationPropertiesValidator`
    (`EnableConfigurationProperties.VALIDATOR_BEAN_NAME`);
  - бин возвращает `IocConfigPreflight`;
  - bean должен быть `static`, чтобы Boot мог поднять validator на ранней фазе
    binding без преждевременной инициализации обычного config graph.
- `bootstrap/ioc-app/.../IocProperties.java`
  - compact-конструкторы оставить только для дефолтов и нормализации
    (`pipeline == null → default`, `ChangeNotify.disabled()`, `List.copyOf`);
  - все проверки, которые сейчас бросают `IllegalArgumentException`
    (`dataframe.type`, `lookup.*`, `sync.retry`, `sync.endpoint`,
    `fetch/publish endpoint refs`, `sync publish export profile`,
    `id.start` без public id), перенести в preflight rules;
  - нормализация коллекций должна быть null-safe: не бросать NPE до JSR-380 /
    preflight, а копировать только non-null значения.
- `bootstrap/ioc-app/src/test/java/.../IocPropertiesBindingTest.java`
  - новый тестовый harness через `ApplicationContextRunner` или минимальный
    `SpringApplication`/`SpringApplicationBuilder`, загружающий настоящий
    `application.yml`;
  - helper для overrides через `TestPropertyValues`, temp overlay и CLI-like
    args.
- Существующий `IocPropertiesTest`
  - оставить для pure Binder/default/parser-level проверок либо постепенно
    перенести негативные сценарии в новый Boot binding harness.

**Тесты:**

- default `application.yml` поднимает `IocProperties` через настоящий Boot
  binding path;
- несколько semantic errors на построенной конфигурации попадают в один
  `BindValidationException`/failure report;
- legacy `ioc.lookup.deduplicate`, removed lookup storage keys,
  non-jdbc dataframe type, invalid sync retry/backoff/profile refs
  больше не падают из compact ctor, а приходят как field errors;
- missing/null nested values не дают NPE в constructors, а проходят через
  JSR-380/preflight.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,IocPropertiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `CFG preflight lifecycle and constructor migration`.
Можно объединить со срезом 2, если diff небольшой, но лучше
сначала закрепить lifecycle без новых правил.

### Срез 2 — Config→config ссылочная целостность для artifacts/identity (выполнен: `9bb9e63`)

**Цель.** Закрыть худший CFG-2 risk: ошибка `artifact-identity` не должна
создавать мусорную durable identity-запись, падать на первой записи или
молчаливо портить `row_key`.

**Изменения кода:**

- `IocConfigPreflight`
  - добавить rule `validateArtifactIdentityReferences`;
  - построить индекс `sink.artifacts[].name → artifact` без IO;
  - проверить уникальность `sink.artifacts[].name`;
  - проверить уникальность `artifact-identity.artifacts[].name`;
  - проверить уникальность `sink.artifacts[].columns[].name` внутри каждого
    artifact;
  - `artifact-identity.artifacts[].name` обязан ссылаться на существующий
    sink artifact; `enabled=false` допустим;
  - каждый `enabled` sink artifact обязан иметь identity definition;
  - `key-columns[]` обязаны быть подмножеством колонок соответствующего artifact;
  - явный numeric `id.start` запрещён для artifact без public `id` column;
  - ошибки должны указывать точный путь:
    `ioc.artifact-identity.artifacts[2].key-columns[1]`,
    `ioc.sink.artifacts[0].columns[3].name` и т.п.
- `bootstrap/ioc-app/.../AppConfig.java`
  - после preflight `artifactIdentityDefinitions(props)` может считать
    `keyMode` и `keyColumns` уже корректными; не добавлять повторную
    ad hoc-валидацию.

**Тесты:**

- typo in identity artifact name (`mask` vs `masks`) fails at startup;
- enabled sink artifact without identity fails at startup;
- identity for disabled-but-existing artifact is accepted;
- duplicate sink artifact names fail;
- duplicate identity artifact names fail;
- duplicate column names in one artifact fail;
- typo in composite `key-columns` fails before JDBC graph/first write;
- explicit numeric `id.start` on artifact without `id` column fails with
  operator-facing message;
- multiple identity/sink mistakes are reported together when binding shape is
  otherwise valid.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `CFG validate artifact identity references`.
Blast radius — bootstrap tests + no runtime IO, поэтому этот срез можно
объединить со срезом 1 при аккуратной реализации.

### Срез 3 — Strict binding, metadata и legacy FailureAnalyzer (выполнен: `ec14c8d`)

**Цель.** Сделать неизвестный ключ под `ioc.*` startup failure, удалить
tombstone-поля из модели и сохранить адресные подсказки миграции.

**Изменения кода:**

- `bootstrap/ioc-app/.../IocProperties.java`
  - поставить `@ConfigurationProperties(prefix = "ioc",
    ignoreUnknownFields = false)`;
  - удалить tombstone `Lookup` record и поле `lookup`;
  - удалить `Sync.Endpoint.Smb.readTimeout`;
  - удалить preflight-rule для legacy lookup как bound property; эти ключи
    теперь должны попадать в unbound/unknown flow.
- `bootstrap/ioc-app/pom.xml`
  - добавить `spring-boot-configuration-processor` только в runnable module,
    optional/annotation-processor style; не добавлять в parent dependencies и
    не протаскивать в runtime classpath.
- `bootstrap/ioc-app/.../IocConfigurationFailureAnalyzer.java`
  - custom `FailureAnalyzer` для
    `UnboundConfigurationPropertiesException` / binding failures;
  - маленькая карта legacy key/prefix → подсказка:
    `ioc.lookup.deduplicate` → `moved to ioc.pipeline.deduplicate`,
    `ioc.lookup.*` → `legacy CSV lookup removed`,
    `ioc.sync.endpoints[].smb.read-timeout` → `use request-timeout`;
  - Description/Action используют operator language и, если решено, стабильные
    коды `CONFIG.*`;
  - analyzer не зависит от `DiagnosticSink` / platform diagnostics.
- `bootstrap/ioc-app/src/main/resources/META-INF/spring.factories`
  - зарегистрировать analyzer:
    `org.springframework.boot.diagnostics.FailureAnalyzer=...`.
- Если `ignoreUnknownFields=false` на root record не покрывает нужные cases:
  - вместо YAML-preparser использовать `ConfigurationPropertiesBindHandlerAdvisor`
    или metadata-preflight;
  - ограничить его только `IocProperties`/prefix `ioc`, чтобы не ломать
    `IngestAdapterProperties(prefix = "ioc.ingestion")`.

**Тесты:**

- YAML typo under classpath/default-style overrides (`ioc.pipeline.deduplicat`)
  fails as unknown key;
- typo in optional overlay `./configs/application.yml` fails;
- CLI override `--ioc.pipeline.deduplicat=false` fails;
- system env/system properties do not produce false failures from unrelated
  `IOC_*`/system keys;
- old `ioc.lookup.deduplicate` and `smb.read-timeout` produce analyzer output
  with migration hints;
- valid env/CLI overrides for known keys still work;
- `IngestAdapterProperties` binding in daemon context is not broken by any
  custom strict-binding fallback.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,IocConfigurationFailureAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `CFG enable strict binding and legacy analyzer`.
Не смешивать с value typing: при регрессе operator startup UX проще отлаживать
strict binding отдельно.

### Срез 4 — Typed selectors и единая грамматика ранних consumers (выполнен: `da27b24`, fix `30f1884`)

**Цель.** Убрать stringly-typed закрытые словари из `IocProperties` и из
потребителей, включая ранние места, где root properties ещё не связаны.

**Изменения кода:**

- `bootstrap/ioc-app/.../config` или `bootstrap` package
  - ввести enum/value types:
    `EngineType`, `RuntimeMode`, `ObservabilityMode`, `StorageType`,
    `ArtifactKeyMode`, `ArtifactIdStrategy`, `ExportTriggerType`,
    `ExportOutputMode`, `IngestionLedgerType`, `SyncTransport`,
    `RetentionActionType` (имена можно уточнить по локальному стилю);
  - для selectors, читаемых до биндинга, добавить общий parser/normalizer
    (`RuntimeMode.parse(...)`, `StorageType.parse(...)`, etc.) с тем же
    lenient behaviour, что Boot enum binding: case-insensitive, dash/underscore
    tolerant where appropriate.
- `IocProperties`
  - заменить string-поля закрытых словарей на enum/value types;
  - убрать literal validation для этих словарей из preflight, где binding уже
    делает значение непредставимым;
  - оставить preflight только для policy-связок между typed values.
- `AppConfig`, `SyncConfig`, `ExportPlanCatalog`,
  `DaemonWebEnvironmentPostProcessor`, `IocExtractorApplication`,
  `EarlyCliLauncher`
  - заменить `equalsIgnoreCase`/`valueOf(trim().toUpperCase(...))` на enum
    switch/typed checks или общий parser для early env reads;
  - `@ConditionalOnProperty`/`@ConditionalOnExpression` остаются строковыми
    только там, где Spring annotation model не позволяет typed access; строки
    берутся из constants/parser vocabulary, а не пишутся заново.
- `ExportPlanCatalog`
  - `outputMode` получает уже typed `ExportOutputMode`;
  - unsupported `append` сохраняет текущий diagnostic path
    (`EXPORT.UNSUPPORTED_MODE`) как business-policy failure, а не bind failure.
- `SyncConfig`
  - `SyncTransport.SMB` вместо `"smb".equalsIgnoreCase(...)`;
  - unsupported transport превращается в bind/preflight failure либо остаётся
    typed enum bind failure, если пока поддерживается только `SMB`.

**Тесты:**

- valid dash/case variants bind to enums (`first-non-empty`,
  `quiet-period`, `Re2J`, `DAEMON`);
- invalid selector value fails during binding with useful valid values;
- early runtime mode parser drives `DaemonWebEnvironmentPostProcessor` and
  `IocExtractorApplication` consistently with `IocProperties`;
- `ExportPlanCatalog` still rejects unsupported but syntactically valid
  `append` as `EXPORT.UNSUPPORTED_MODE`;
- sync transport typo fails before `TransportRegistry`;
- retention action typo fails before `RetentionService` graph.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,ExportPlanCatalogTest,SyncSelectionCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `CFG type closed configuration selectors`.
Если diff становится слишком широким, допустимо разбить на два коммита:
`engine/runtime/storage/export` и затем `sync/ingestion/retention/identity`.
Оба коммита должны двигаться к одной целевой модели и не оставлять две
грамматики для одного selector'а.

### Срез 5 — `id.start` value contract и удаление silent fallback (выполнен: `3d45cdd`, fix `e01e2fa`)

**Цель.** Закрыть CFG-1 полностью: `id.start` становится `"auto" | long`
value contract с одной грамматикой для binding/preflight/runtime.

**Изменения кода:**

- `bootstrap/ioc-app/.../IdStart` / `ConfiguredIdStart`
  - ввести lightweight value object или sealed hierarchy:
    `auto`, `explicit(long)`;
  - добавить единый parser с operator-facing error message;
  - при выборе typed binding — зарегистрировать
    `@ConfigurationPropertiesBinding Converter<String, IdStart>`;
  - при выборе более лёгкого пути — хранить строку в `IocProperties`, но
    использовать тот же parser в preflight и `AppConfig.startOf`.
- `IocProperties.Sink.Artifact.Id`
  - `start` становится `IdStart` либо остаётся raw string только если общий
    parser/value contract оформлен отдельно и покрыт тестами.
- `AppConfig.startOf(...)`
  - удалить `catch (NumberFormatException ignored)`;
  - switch/pattern-match по `IdStart` или результату общего parser;
  - invalid value не может попасть сюда после preflight; если всё же попал —
    бросать `IllegalStateException`/`IocExtractorException`, а не fallback to
    `auto`.
- `ExportPlanCatalog.mappingHash(...)`
  - hash должен учитывать нормализованное значение `auto`/explicit start
    детерминированно, чтобы формат mapping hash не зависел от case/spacing.

**Тесты:**

- `id.start: auto`, `AUTO`, ` auto ` дают auto semantics;
- numeric start даёт explicit start;
- `10O0`, кириллическое `аuto`, пустая строка и overflow fail at startup;
- explicit numeric start на artifact без public id всё ещё fail;
- `AppConfig.startOf` не вызывает baseline fallback при invalid literal;
- mapping hash стабилен для normalized `auto`.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,ExportPlanCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `CFG parse id start explicitly`.
Можно объединить со срезом 4, если typed selector migration уже небольшая; при
сомнениях держать отдельно, потому что это отдельный tagged-union contract.

### Срез 6 — Registry checks, eager startup и сообщения (выполнен: `8575527`)

**Цель.** Довести config→registry проверки до того же startup UX: неизвестные
predicate/provider/transform/filter keys не должны проявляться в первой
обработке файла или записи.

**Изменения кода:**

- `AppConfig` / новый bootstrap preflight component
  - явно проверить classify predicate keys против `FeaturePredicates.defaults()`;
  - проверить artifact `include`/`exclude` keys против registry из
    `artifactFilters(...)`;
  - проверить `columns[].from` против value provider registry плюс `const`;
  - проверить `columns[].transform[]` names против transforms registry
    (`name:arg` разбирается по имени);
  - ошибки привести к operator language: property path, bad value, allowed
    values/action.
- `ConfigurableRowMapper`
  - runtime guard можно оставить defence-in-depth, но он уже не должен быть
    первым штатным местом обнаружения ошибки конфига.
- Bean lifecycle
  - убедиться, что эти registry checks eager в oneshot/daemon startup paths;
  - не делать тяжёлого IO и не поднимать lazy export/sync infrastructure только
    ради проверки registry keys.

**Тесты:**

- unknown classify predicate fails at startup with path
  `ioc.classify.rules[n].when[m]`;
- unknown artifact include/exclude predicate fails at startup;
- unknown column provider fails at startup;
- unknown transform fails at startup, including `name:arg` parsing;
- valid `const`, transform args and `when-type` remain accepted;
- failure messages do not mention internal stage numbers or implementation
  method names.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,ConfigRegistryPreflightTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `CFG validate registry-backed config eagerly`.
Этот срез можно объединить со срезом 2 только если registry maps уже доступны
без раздувания `IocConfigPreflight`; иначе держать отдельно, чтобы не смешать
pure config→config и config→registry boundaries.

### Срез 7 — Документы, tracking и финальная верификация (выполняется текущим docs/tracking commit)

**Цель.** Перевести ADR/worknote/KNOWN-ISSUES из design-state в shipped-state
после кода и закрепить operator-facing contract в документации.

**Изменения документации:**

- `docs/ADR/0016-config-preflight-strict-binding.md`
  - append-style обновить «Статус»: дата, commit range, какие срезы shipped;
  - в этом implementation plan пометить выполненные срезы с commit ids;
  - не переписывать исторический rationale.
- `docs/worknote/config-hardening.md`
  - отметить route as implemented, оставить как evidence/implementation ledger;
  - убрать или зачеркнуть открытые вопросы, закрытые кодом.
- `docs/KNOWN-ISSUES.md`
  - `CFG-1`, `CFG-2`, `CFG-4` перевести в `закрыт` со ссылкой на ADR/коммиты;
  - `CFG-3` закрыть ссылкой на storage collapse / удаление старого сообщения;
  - строки не удалять.
- `docs/ADR/README.md`
  - обновить summary ADR 0016 на shipped-state.
- `docs/dev/CROSS-CUTTING.md` или более подходящий dev/root doc
  - добавить короткое описание config preflight lifecycle, strict binding и
    operator error convention, если после реализации появился новый
    устойчивый capability doc-worthy contract.
- `bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/README.md`
  - добавить новые preflight/analyzer/parser классы в reference table;
  - зафиксировать, что они принадлежат composition root.

**Финальная проверка:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Рекомендуемая коммитная форма:**

1. `CFG preflight lifecycle and constructor migration`
2. `CFG validate artifact identity references`
3. `CFG enable strict binding and legacy analyzer`
4. `CFG type closed configuration selectors`
5. `CFG parse id start explicitly`
6. `CFG validate registry-backed config eagerly`
7. `Docs mark config preflight shipped`

Если реализация идёт одним проходом, допустимые объединения:
срезы 1+2 (один preflight lifecycle/reference commit), срезы 4+5 (typed selectors +
`id.start` grammar), срезы 6+7 только если registry checks малы и docs не
маскируют кодовый diff. Срез 3 лучше держать отдельно: strict binding и
FailureAnalyzer имеют отдельный startup UX blast radius.
