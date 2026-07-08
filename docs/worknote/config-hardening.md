# Worknote: надёжность конфигурации (блок CFG)

**Статус:** рабочая дизайн-дока (НЕ ADR). Создана 2026-07-08 по анализу блока
`CFG` из [KNOWN-ISSUES](../KNOWN-ISSUES.md). Цель — не точечные затычки, а
единый подход к валидации `ioc.*`-конфига на инструментах, уже присутствующих
в дереве зависимостей (Spring Boot binding/validation, JSR-380), и на
общепринятых паттернах.

**Обновление 2026-07-08: все дизайн-вопросы решены, подход зафиксирован в
[ADR 0016](../ADR/0016-config-preflight-strict-binding.md)** (принято, не
реализовано). Итоговая модель: **единый startup preflight для `ioc.*`,
strict binding unknown/deprecated keys, collect-all semantic validation,
config→config referential integrity, полная типизация закрытых словарей,
единая грамматика для ранних consumers и `IocProperties`, tombstones вне
модели + FailureAnalyzer без связки с diagnostics**. Этот worknote остаётся
рабочим списком реализации (маршрут, проверки, слайсы), но слайсы не меняют
целевую модель; «почему» — в ADR.

## Постановка (общий корень)

`ioc.*` — это не «настройки», а **DSL с типизированными значениями и
символьными ссылками между секциями** (artifact-identity → sink, export →
sink, classify → predicate registry, columns.from → provider registry).
Валидация этого DSL сегодня размазана по трём идиомам и четырём моментам
времени:

| Идиома | Где применяется | Момент срабатывания |
|---|---|---|
| JSR-380 аннотации (`@NotBlank`, `@Valid`) | вся `IocProperties` | биндинг ✅ |
| Compact-constructor валидации | tombstones `ioc.lookup.*`, `dataframe.type`, `id.start`×id-колонка | биндинг ✅ |
| Registry-throws при создании бинов | classify-предикаты (AppConfig:219), artifact-фильтры (:979), провайдеры/трансформы `ConfigurableRowMapper`, export-профили (`ExportPlanCatalog:140`) | старт контекста ✅ |
| — (ничего) | CFG-2 (identity↔sink, key-columns↔columns, key-mode), CFG-1 (мусорный `id.start`) | первая запись / никогда ❌ |
| Relaxed binding | любой неизвестный ключ `ioc.*`, кроме двух ручных tombstones | никогда ❌ |

Паттерны, под которые ложится целевое состояние: **fail-fast configuration
validation** (все ошибки конфига — до начала работы), **«parse, don't
validate»** (невалидное значение непредставимо в типе), **referential
integrity preflight** (все ссылки проверяются в одном месте), плюс конвенция
сообщений: ошибка конфига говорит языком оператора (ключ → значение → что
исправить), без внутреннего жаргона (урок CFG-3).

## Инвентарь проблем (верифицировано по коду 2026-07-08)

### CFG-1 — мусорный `id.start` молча уходит в `auto` · S

`id.start` — stringly-typed объединение `"auto" | long`. `catch
(NumberFormatException ignored)` в `AppConfig.startOf` (~:1049) превращает
любую опечатку (`10O0`, кириллическое `аuto`) в молчаливый fallback на
продолжение от `MAX(id)` БД. Влияние ограниченное (id после retirement не
identity), но это самообман конфига. Валидация Фазы 4 retirement
(`validateSinkIdStarts`) сюда не дотягивается: она отбрасывает только
явно-числовой start без id-колонки.

### CFG-2 — битые ссылки identity-конфига · S по эффорту, HIGH по влиянию

Три сценария; худший в реестре не записан:

- **(а)** identity-def на несуществующий артефакт (`mask` vs `masks`) — тихий
  no-op, плюс `artifactIdentityStore.ensureAll` создаёт в identity store
  **durable-запись для несуществующего артефакта** (мусор в служебной БД).
- **(б)** enabled sink-артефакт **без** identity-def — падение не на старте, а
  на **первой записи**: `CanonicalArtifactIdentityResolver.keyOf` → empty →
  «Cannot resolve row_key for artifact X» из `JdbcCanonicalArtifactRepository`.
  В daemon файл уходит в retry/failed; сообщение не намекает на конфиг.
- **(в) — глубже всего:** опечатка в **одной из** composite `key-columns` →
  `compositeJson` кодирует несуществующую колонку как явный `null` и всё
  равно выдаёт хеш, пока жива хоть одна настоящая колонка → **неверный
  `row_key` пишется в БД навсегда** (дедуп по усечённому ключу). Исправление
  опечатки позже = identity drift → HALT guard → epoch-миграция. Тихая
  опечатка конвертируется в data-integrity-инцидент.
- **(г) — попутная находка:** `key-mode` — тоже stringly-typed:
  `"first-non-empty".equalsIgnoreCase(...)`, любая опечатка
  (`first-nonempty`) молча даёт composite-режим.

Влияние: `row_key` — фундамент keep-first/provenance/stable id. Образец
решения уже в кодбазе: `ExportPlanCatalog` отбивает профиль с «references
unknown or disabled sink artifact».

### CFG-3 — «stage 11» в операторской ошибке · ЗАКРЫТ кодом

Сообщение жило в `validateAggregationConfig`
(`"…not supported at stage 11"`) — метод удалён вместе с aggregation при
β-collapse. Grep по актуальному коду утечек внутренней нумерации не находит
(«stage started/completed» в observability — имена стадий пайплайна,
легитимно). **Действие:** закрыть строку в KNOWN-ISSUES (ссылка: storage
collapse), а классу проблемы дать жизнь как конвенции сообщений (см. ниже).

### CFG-4 — relaxed binding + растущие ручные tombstones · S/M

Неизвестный ключ под `ioc.*` молча игнорируется (`qiet-period`,
`deduplicat: false` → тихие дефолты). Против уже выведенных ключей — два
**рукописных** tombstone (`Sync.Endpoint.Smb.readTimeout`, весь record
`Lookup` после retirement): приём рабочий, но каждый вывод ключа требует
нового ручного стража; поля-призраки живут в типах вечно. Это системный
родитель всего блока: строгость по ключам закрывает целый класс опечаток
разом и позволяет удалить tombstones из модели.

## Целевые инварианты

1. **Ошибка конфига не переживает старт.** Ни одна не доживает до первой
   записи; в daemon — до первого файла.
2. **Единый операторский отчёт.** Unknown/deprecated keys, binding/conversion
   failures, JSR-380, локальные инварианты и config→config ссылки относятся к
   одному startup preflight. Семантические ошибки на уже связанной форме
   конфигурации собираются пачкой с property-путями; malformed YAML,
   conversion/enum failures и unknown-key failures могут остановить binding до
   части semantic-проверок, потому что объект ещё физически не построен.
3. **Ссылочная целостность в одном месте.** Все символьные ссылки
   config→config проверяются одним preflight'ом; ссылки config→registry
   остаются на создании бинов (им нужны реестры), но обязаны быть eager.
4. **Невалидное значение непредставимо** для закрытых словарей и selector'ов:
   runtime/storage/sync/ledger/export/identity/action/mode selectors становятся
   enum/value types; ранние consumers и `IocProperties` используют одну
   грамматику.
5. **Неизвестный ключ = ошибка**, ручные tombstones удаляются из модели;
   адресные подсказки миграции («moved to …») живут не в типах.
6. **Конвенция сообщений:** ключ → значение → как исправить; язык оператора.

## Механика: как конфиг проходит от файла до валидного объекта

Собственного кода чтения файла в проекте нет — вся труба принадлежит Spring
Boot; наш код только декларирует форму (`IocProperties`) и источники
(`spring.config.import`). Этапы и где встраиваются наши варианты:

1. **Чтение (ConfigData API, до создания контекста).**
   `SpringApplication.run` → `ConfigDataEnvironmentPostProcessor` находит
   `classpath:application.yml` по конвенции, парсит `YamlPropertySourceLoader`
   (SnakeYAML) и кладёт в `Environment`; строка
   `spring.config.import: optional:file:./configs/application.yml` подключает
   операторский overlay. CLI-аргументы и env-переменные становятся отдельными
   property sources автоматически. Lightweight `--help` (`EarlyCliLauncher`)
   выходит ДО этого этапа — конфиг не читается вовсе.
2. **Environment = упорядоченный стек `PropertySource`.** «Файла» дальше не
   существует; override-порядок из шапки yml — это порядок слоёв стека
   (CLI > env > overlay > classpath).
3. **Binding.** `@ConfigurationPropertiesScan` регистрирует record;
   `Binder` конструирует дерево вложенных records снизу вверх: relaxed
   binding (`quiet-period` ↔ `quietPeriod`), конверсии (String→Duration).
   **Только Binder знает, какие ключи он потребил** — поэтому строгость по
   неизвестным ключам (B1/B2) может жить только здесь
   (`NoUnboundElementsBindHandler`).
4. **Валидация — два слоя внутри binding-фазы + один снаружи:**
   compact-конструкторы вложенных records срабатывают ПО ХОДУ биндинга
   (снизу вверх; поэтому tombstone-исключения приходят завёрнутыми в
   `BindException`); JSR-380-аннотации — ПОСЛЕ конструирования, через
   `ValidationBindHandler` (включён `@Validated`); registry-проверки — позже,
   на context refresh при создании бинов AppConfig. Планируемый
   `configurationPropertiesValidator` (A2) встраивается в тот же
   `ValidationBindHandler` рядом с JSR-380 — чтение и биндинг не трогаем.

## Дизайн-варианты

### A. Шов для валидаций: где им жить

- **A1 — статус-кво+: compact constructor `IocProperties`.** Идиома уже есть
  (Фаза 4). Минус: throw-first (оператор чинит по одной ошибке за прогон),
  без property-путей, конструктор уже ~60 строк валидаций и растёт.
- **A2 (рекомендую) — стандартный Spring-хук `configurationPropertiesValidator`:**
  бин `org.springframework.validation.Validator` с этим именем (static
  `@Bean`) вызывается самим Boot **во время биндинга**. Даёт `Errors` →
  собираем **semantic**-нарушения на построенной форме конфигурации с
  field-путями (`ioc.artifact-identity.artifacts[2].key-columns[1]`), Boot сам
  заворачивает в `BindValidationException`, у которой уже есть
  штатный `FailureAnalyzer` с человеческим выводом. Это канонический
  Spring-механизм кросс-полевой валидации конфига — ровно наш случай.
  Compact constructor остаётся для инвариантов одного record'а
  (нормализация `pipeline == null → default`), кросс-секционные проверки
  переезжают в валидатор.
- Контраргумент к A2: ещё один класс + требование static-бина. Перевешивает
  collect-all + пути: CFG-2 — это 3–4 проверки на секцию, оператор должен
  видеть их разом.

**Решено (2026-07-08): A2, с полной миграцией.** Уточнение границы: compact
constructors НЕ исчезают, а сужаются до нормализации/дефолтов
(`pipeline == null → default`, `ChangeNotify.disabled()`, `List.copyOf`) и
**перестают бросать по операторским ошибкам**. Причина жёсткой границы: если
compact ctor бросил во время биндинга, объект не сконструирован и Validator
уже не запустится — смешанный режим маскирует часть ошибок первой брошенной.
Один канал ошибок = все операторские проверки (локальные инварианты Retry/Smb,
кросс-секционные Sync↔Export, D-проверки) уезжают в Validator; JSR-380
аннотации остаются (ValidationBindHandler агрегирует их с нашим Validator в
общий `Errors`).

**Структура реализации:** единый Spring-facing фасад
`IocConfigPreflight` / `IocConfigValidator` держит lifecycle и общий отчёт,
но сами проверки раскладываются на маленькие правила: shape,
reference-integrity, literals/value-types, policy. Это сохраняет один канал
ошибок без god-validator.

### B. Строгий binding (CFG-4)

- **B1 (первым, проверить) — `@ConfigurationProperties(ignoreUnknownFields = false)`.**
  Один атрибут. Под капотом — `NoUnboundElementsBindHandler`;
  `UnboundElementsSourceFilter` исключает systemEnvironment/systemProperties,
  так что env-переменные не должны давать ложных отказов — **но связку
  constructor binding (records) + Boot 3.3.5 надо проверить экспериментально**
  (тест: опечатанный ключ в YAML → отказ; `IOC_*` env и `--ioc.*` CLI →
  живы; overlay `./configs/application.yml` → тоже строгий).
- **B2 (если B1 упрётся) — собственный preflight:** пройтись по
  `ConfigurableEnvironment`, собрать `ioc.*`-ключи, сверить с
  `spring-configuration-metadata.json`.
- **B3 (в любом случае, дёшево) — подключить `spring-boot-configuration-processor`**
  в `bootstrap`: генерирует метаданные всех валидных ключей → автодополнение
  операторского YAML в IDE; основа для B2, если понадобится.
- **Судьба tombstones и качество сообщений:** строгий binding даёт generic
  «elements … were left unbound» — теряем адресное «moved to
  ioc.pipeline.deduplicate». Решение: **кастомный `FailureAnalyzer`** для
  unbound-ошибки с маленькой картой legacy-ключ → подсказка
  (`ioc.lookup.deduplicate` → «moved…», `read-timeout` → «use
  request-timeout»). Тогда tombstone-поля (`readTimeout`, record `Lookup`)
  удаляются из модели полностью, а подсказки живут в одном analyzer'е —
  закрывает CFG-4 в формулировке реестра.

**Уточнение после review:** если B1 придётся заменить кастомным binding-швом,
предпочтительный fallback — официальный
`ConfigurationPropertiesBindHandlerAdvisor` или metadata-preflight, но
строго ограниченный root `IocProperties`/prefix `ioc`. В проекте есть
adapter-local binding `IngestAdapterProperties(prefix = "ioc.ingestion")`;
глобальный advisor без фильтра может ошибочно считать соседние ingest-ключи
unbound при поддеревном binding.

### C. Типизация значений (CFG-1, CFG-2г)

Инвентарь stringly-typed полей делится на два сорта:

- **Закрытые словари (enum-like):** `key-mode`, `id.strategy`,
  `export.trigger.type`, `profile.output-mode`, `ingestion.ledger.type`,
  `runtime.mode`, `observability.mode`, `engine`, `storage.*.type`,
  `sync.endpoint.transport`, `maintenance.retention.targets[].action` и другие
  однотипные selectors. Целевая модель — все они становятся enum/value types,
  а потребители перестают сравнивать строки (`equalsIgnoreCase`). Для простых
  случаев custom-converter НЕ нужен: Spring биндит String→Java-enum из коробки
  (включая `first-non-empty` → `FIRST_NON_EMPTY`, лениентный enum-конвертер
  понимает дефисы/регистр), а неизвестный литерал автоматически становится
  bind-ошибкой с внятным сообщением — «parse, don't validate» бесплатно.
  Если selector нужен до биндинга (`EnvironmentPostProcessor`,
  `@ConditionalOnExpression`/`@ConditionalOnProperty`, lightweight CLI),
  рядом вводится общий parser/normalizer, чтобы ранний consumer и
  `IocProperties` не держали две разные грамматики.
- **Tagged union (значение = маркер ИЛИ payload):** во всём `ioc.*` ровно
  одно такое поле — `id.start` (`"auto" | long`). Ему нужен единый parser/value
  contract, используемый и preflight'ом, и `AppConfig.startOf`; sealed-тип +
  `@ConfigurationPropertiesBinding`-Converter остаётся допустимой финальной
  формой, но не обязателен, если общий parser уже держит инвариант.

Варианты:
- **C1 (страховочный слой):** валидация литералов в preflight:
  `id.start ∈ {"auto", целое}` и словари выше. Мёртвый `catch` в
  `startOf` удаляется. В целевом дизайне это не замена типизации, а переходный
  или дополнительный guard там, где binding-shape ещё не даёт value type.
- **C1.5 (целевое решение для словарей):** перевести закрытые словари на
  enum/value поля record'ов и выровнять всех потребителей, включая ранние
  string-based gates. Это не “частичный слайс”, а консистентное целевое
  состояние; реализацию можно разбивать позже.
- **C2 (`id.start` как value object):** sealed `IdStart { Auto, Explicit(long) }`
  + Converter или более лёгкий общий parser/value contract. Выбор формы —
  реализационная деталь; обязательный дизайн-инвариант — одна грамматика для
  preflight и runtime.

### D. Ссылочная целостность (CFG-2) — состав проверок

В preflight'е, чистые config→config:

1. `artifact-identity.artifacts[].name` ⊆ `sink.artifacts[].name` (а);
2. каждый **enabled** sink-артефакт имеет identity-def (б) — превращает
   «Cannot resolve row_key» первой записи в ошибку старта;
3. `key-columns[]` ⊆ колонок соответствующего артефакта (в);
4. уникальность `sink.artifacts[].name`, `artifact-identity.artifacts[].name`
   и `sink.artifacts[].columns[].name` внутри каждого артефакта;
5. `id.start`/`id.strategy` согласованы с public `id`-колонкой: явный numeric
   start запрещён для артефакта без public id;
6. литералы `key-mode`/`strategy`/`start` (г, C1/C1.5);
7. заодно закрыть остаток CFG-2 из реестра: сверка уже есть у export-профилей —
   формулировку в KNOWN-ISSUES обновить на «закрыто preflight'ом».

Config→registry проверки (classify-предикаты, `columns.from`, transforms) —
остаются на создании бинов; проверить, что все соответствующие бины eager.

## Маршрут реализации (решение — в ADR 0016)

Ниже — возможный маршрут, а не урезание дизайн-решения. Целевая модель цельная:
единый preflight, строгий binding, typed selectors, единая грамматика ранних и
поздних consumers. Тонкие слайсы допустимы только как способ безопасно прийти к
этому состоянию; гейт каждого — `./mvnw verify`:

1. **Слайс 1 (CFG-2, наибольшая ценность):** `IocConfigValidator`
   (`configurationPropertiesValidator`, static bean) + миграция операторских
   проверок из compact-конструкторов (конструкторы — только
   нормализация/дефолты) + проверки D + тесты через настоящий Boot
   binding/startup path (`ApplicationContextRunner`/минимальный
   `SpringApplication`), а не только ручной `Binder`.
2. **Слайс 2 (CFG-4):** эксперимент B1 (`ignoreUnknownFields=false` на
   records/Boot 3.3.5: yaml-опечатка / env / CLI / overlay) → строгость +
   configuration-processor (B3) + `FailureAnalyzer` с картой legacy-подсказок
   → удалить оба tombstone из `IocProperties` (tombstone-тесты → analyzer-тесты
   с тем же контрактом сообщений).
3. **Слайс 3 (типизация + единая грамматика):** C1.5 enum/value selectors для
   всех закрытых словарей и выравнивание ранних consumers; единый
   parser/value contract для `id.start`, удалить мёртвый catch (CFG-1);
   KNOWN-ISSUES: закрыть CFG-1..4 (CFG-3 — ссылкой на storage collapse);
   обновить `docs/dev/cross-cutting.md` / конфиг-доки, если затронуты.

## Открытые вопросы

1. ~~**Шов:** A2 vs compact-constructor~~ — **решено (2026-07-08): A2 с полной
   миграцией**; конструкторы сужаются до нормализации/дефолтов и не бросают
   по операторским ошибкам (см. блок «Решено» в разделе A).
2. ~~**Identity-def на disabled-артефакт**~~ — **решено (2026-07-08,
   подтверждено пользователем): ссылка обязана указывать на существующий
   артефакт, `enabled` не требуем** — identity безвредна и переживает
   временное отключение артефакта (в отличие от export-профилей, где
   disabled = unknown: там доставка).
3. ~~**Deprecation-подсказки**~~ — **решено (2026-07-08): FailureAnalyzer
   используем** (Description/Action, `@Order`). **К Diagnostics НЕ
   подвязываем:** (а) разные жизненные циклы — `Diagnostic` = событие
   работающего пайплайна (collect-and-continue, ECS, каталог кодов),
   FailureAnalyzer = терминальное объяснение оператору, когда процесс
   умирает на старте; (б) технически analyzer инстанцируется через
   `spring.factories` вне полностью собранного контекста — при падении на
   биндинге бина `DiagnosticSink` ещё/уже нет, связка была бы хрупкой;
   (в) у boot-фейла нет потребителя ECS-потока. Допустимое заимствование —
   только конвенция стабильных кодов в тексте сообщений (grep-ability),
   без DiagnosticSink.
4. ~~**C2 (sealed IdStart)**~~ — **уточнено (2026-07-08):** вопрос не в форме
   sealed-типа, а в единой грамматике. `id.start` обязан иметь общий
   parser/value contract для preflight и runtime; sealed converter остаётся
   допустимой, но не обязательной реализационной формой.

## Верификация (когда дойдём до кода)

- Тесты уровня биндинга/startup: по negative-тесту на каждую проверку D +
  строгость B1 (yaml-опечатка, env, CLI, overlay) через
  `ApplicationContextRunner`/минимальный `SpringApplication`; pure `Binder`
  оставляем только для локальных defaults/parser-тестов.
- Существующие негативные пути не регрессируют: tombstone-тесты заменяются
  на analyzer-тесты тем же контрактом сообщений.
- `./mvnw verify` — гейт каждого слайса.
