# 0016 — Надёжность конфигурации: единый preflight + строгий binding

## Статус

**Принято 2026-07-08, не реализовано.** Закрывает блок `CFG` из
[KNOWN-ISSUES](../KNOWN-ISSUES.md) (CFG-1, CFG-2, CFG-4; CFG-3 уже закрыт
удалением кода при β-collapse) как один класс проблем, а не точечными
фиксами. Инвентарь проблем, верификация по коду, механика binding-трубы и
порядок реализации — в
[worknote/config-hardening.md](../worknote/config-hardening.md).

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
