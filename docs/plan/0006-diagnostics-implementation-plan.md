# План реализации этапа 6: diagnostics

## Цель

Реализовать подсистему диагностики обработки данных из
[diagnostics.md](../diagnostics.md): неизменяемое диагностическое ядро, каталог
кодов, `Result`/`Notification`, `FailurePolicy`, порт `DiagnosticSink` и
генерацию карты ошибок из каталога.

Этап 6 должен дать устойчивую основу для этапа 7 (`Stage`/`Envelope`/pipeline) и
этапа 8 (observability/logging), но не должен реализовывать их ответственность.

## Границы этапа

Входит в этап:

- модель `Diagnostic` как данные обработки, без форматирования сообщений в
  producer-коде;
- перечислимый каталог кодов с category/severity/message key/default template;
- накопление диагностик через `Result<T>` и `Notification`;
- политика `FailurePolicy`: `fail-fast` и `collect-and-continue`;
- порт `DiagnosticSink` и базовые реализации `CollectingDiagnosticSink`,
  `NoopDiagnosticSink`;
- порт `DiagnosticRenderer` и простой renderer поверх дефолтных шаблонов
  каталога;
- генерация/проверка карты ошибок из каталога;
- unit- и ArchUnit-тесты границ.

Не входит в этап:

- `Envelope.meta`, `Stage`, рефакторинг всего конвейера в Pipes-and-Filters
  (это этап 7);
- ECS, MDC, `MdcScope`, rolling JSON logs, `LoggingDiagnosticSink`
  (это этап 8);
- daemon/stream ingestion, ledger, dead-letter storage (это этап 10);
- массовая замена всех текущих исключений и веток пайплайна на `Result`;
- `MessageCatalog`/override bundle поверх дефолтных шаблонов: seam описан в
  [diagnostics.md](../diagnostics.md), но реализация откладывается до i18n-шага;
- локализация через Spring `MessageSource`, если не нужна для минимального
  завершения этапа.

## Карта ответственности

| Уровень | Обязан делать | Запрещено делать |
|---|---|---|
| `diagnostics` core | хранить диагностические данные, коды, severity/category, result/notification/policy контракты | импортировать Spring, SLF4J/Logback, adapters, bootstrap |
| producer-код (`domain`/`application`/`adapter`) | создавать `Diagnostic(code, context, cause)` и отдавать его через результат/исключение/порт | форматировать пользовательские строки и решать, куда отправлять диагностику |
| renderer | превращать code+context в текст по шаблону | менять control-flow пайплайна или писать в лог/файлы |
| sink | принимать готовые `Diagnostic` как события подсистемы | знать про ECS/MDC, pipeline `Envelope`, CSV-артефакты или CLI |
| failure policy | чисто вычислять решение fail-fast vs collect-and-continue по severity/context | выполнять IO, рендерить сообщения, бросать исключения, изменять доменные объекты |

## Целевая структура файлов

Пока проект однопомовый, подсистема живёт в отдельном корневом пакете. При
переходе к многомодульности этот пакет станет кандидатом на модуль
`platform-diagnostics`.

```text
src/main/java/com/iocextractor/diagnostics/
├── README.md
├── package-info.java
├── Diagnostic.java
├── DiagnosticBuilder.java
├── DiagnosticFactory.java
├── DiagnosticCategory.java
├── DiagnosticCode.java
├── DiagnosticException.java
├── DiagnosticSeverity.java
├── codes/
│   ├── README.md
│   ├── package-info.java
│   ├── ConfigDiagnosticCodes.java
│   ├── SourceDiagnosticCodes.java
│   ├── ExtractionDiagnosticCodes.java
│   ├── ClassificationDiagnosticCodes.java
│   ├── SinkDiagnosticCodes.java
│   └── PipelineDiagnosticCodes.java
├── catalog/
│   ├── README.md
│   ├── package-info.java
│   ├── DiagnosticCatalog.java
│   ├── DiagnosticCatalogEntry.java
│   └── DiagnosticCatalogs.java
├── result/
│   ├── README.md
│   ├── package-info.java
│   ├── Result.java
│   ├── Notification.java
│   ├── FailureDecision.java
│   └── FailurePolicy.java
├── render/
│   ├── README.md
│   ├── package-info.java
│   ├── DiagnosticRenderer.java
│   └── TemplateDiagnosticRenderer.java
└── sink/
    ├── README.md
    ├── package-info.java
    ├── DiagnosticSink.java
    ├── CollectingDiagnosticSink.java
    └── NoopDiagnosticSink.java

src/test/java/com/iocextractor/diagnostics/
├── DiagnosticTest.java
├── DiagnosticCatalogTest.java
├── DiagnosticCatalogDocumentationTest.java
├── FailurePolicyTest.java
├── NotificationTest.java
├── ResultTest.java
├── TemplateDiagnosticRendererTest.java
└── sink/CollectingDiagnosticSinkTest.java

docs/diagnostic-catalog.md
```

`docs/diagnostic-catalog.md` должен быть производным от кода. Тест
`DiagnosticCatalogDocumentationTest` генерирует markdown в памяти и сравнивает с
закоммиченным документом. Так карта ошибок не расходится с каталогом.

## Контракты типов

### `DiagnosticCode`

Минимальный контракт:

```java
public interface DiagnosticCode {
    String id();
    DiagnosticCategory category();
    DiagnosticSeverity defaultSeverity();
    String messageKey();
    String defaultMessageTemplate();
}
```

Правила:

- `id()` стабилен и не переиспользуется после удаления кода;
- формат id: `CATEGORY.SPECIFIC_CODE`, например
  `EXTRACTION.INDICATOR_SKIPPED`;
- `messageKey()` стабильный и пригоден для будущего `MessageSource`;
- default template есть всегда, чтобы диагностика была самодостаточной без
  внешних ресурсов.

### `Diagnostic`

`Diagnostic` - immutable value object:

- поля: `code`, `severity`, `category`, `context`, `cause`, `timestamp`;
- `category` берётся из `code`, а не задаётся отдельно producer-ом;
- если severity не переопределён, используется `code.defaultSeverity()`;
- `context` копируется через `Map.copyOf`;
- `timestamp` проставляется через `Clock`, переданный в
  `DiagnosticFactory`/builder; `Instant.now()` внутри модели запрещён;
- `cause` хранится как `Optional<Throwable>` или nullable-поле с безопасным
  accessor-методом;
- equality/hashCode определяются по `code` + `context`; `severity`, `cause` и
  `timestamp` не участвуют, чтобы две диагностики одного факта дедуплицировались
  независимо от времени и способа доставки;
- builder/factory должен требовать `code` и `Clock` и не позволять `null` в
  ключах/значениях context.

### `DiagnosticException`

Фатальные ошибки можно переносить через `DiagnosticException`, который содержит
`Diagnostic` и наследуется от текущего `IocExtractorException` либо является
соседним unchecked-исключением. Предпочтение: наследоваться от
`IocExtractorException`, чтобы не менять CLI/application границы сразу.

Модульное последствие: в одномодульной реализации это зависимость
`diagnostics -> common`. При выносе в `platform-diagnostics` базовый тип ошибки
должен жить в `platform-errors`/common-слое, а не в application/bootstrap.

### `Result<T>`

Рекомендуемый минимальный контракт:

```java
public record Result<T>(T value, List<Diagnostic> diagnostics) {
    boolean hasErrors();
    boolean hasFatal();
    Result<T> withDiagnostic(Diagnostic diagnostic);
    <R> Result<R> map(Function<T, R> mapper);
}
```

На этапе 6 достаточно value + diagnostics. Не нужно внедрять сложный monad API,
accumulating validation DSL или внешние библиотеки. На этапе 7 этот тип станет
контрактом между стадиями pipeline.

`value` может быть `null`, если результат не получен, но диагностики должны
объяснять причину. `map` должен быть null-safe: не вызывает mapper при
`value == null` и переносит diagnostics дальше без потерь.

### `Notification`

`Notification` - mutable collector для одного прогона или элемента:

- `add(Diagnostic)`;
- `addAll(Collection<Diagnostic>)`;
- `diagnostics()`;
- `hasErrors()`;
- `hasFatal()`;
- `toResult(T value)`;
- `throwIfRejected(FailurePolicy policy)`.

Коллектор не пишет в sinks сам. Отправка в sinks остаётся явным шагом на границе
оркестрации.

`Notification` не thread-safe by design. Это per-run/per-item collector: в
будущей многопоточности каждая задача получает свой экземпляр, а агрегация
делается на внешней границе.

### `FailurePolicy`

`FailurePolicy` - strategy:

- `FAIL_FAST`: `ERROR`/`FATAL` дают решение `STOP`;
- `COLLECT_AND_CONTINUE`: `ERROR` даёт `CONTINUE`, `FATAL` может дать `STOP`;
- `evaluate(Notification)` не бросает исключения и не меняет notification.

Формальный выбор:

```java
public interface FailurePolicy {
    FailureDecision evaluate(Notification notification);
}
```

`FailureDecision` может быть enum/record: `CONTINUE`, `STOP`. Не добавлять
конфигурацию Spring на этапе 6: политику можно выбрать кодом, а привязать к
`application.yml` позже при интеграции pipeline.

Исключение бросает не политика, а orchestration boundary, например
`Notification.throwIfRejected(policy)`, когда решение `STOP` должно стать
`DiagnosticException`.

### `DiagnosticSink`

Порт:

```java
public interface DiagnosticSink {
    void emit(Diagnostic diagnostic);
}
```

Базовые реализации:

- `NoopDiagnosticSink` - ничего не делает;
- `CollectingDiagnosticSink` - хранит копию диагностик для тестов/отчёта.

`LoggingDiagnosticSink` не создаётся на этом этапе.

### `DiagnosticRenderer`

Порт:

```java
public interface DiagnosticRenderer {
    String render(Diagnostic diagnostic);
}
```

`TemplateDiagnosticRenderer` использует `code.defaultMessageTemplate()` и
подставляет значения из `context`. Шаблонный синтаксис должен быть простым:
`{indicator}`, `{source}`, `{reason}`. Если значения нет, плейсхолдер остаётся
видимым или заменяется на `<missing>` - решение фиксируется тестом.

`MessageCatalog` из [diagnostics.md](../diagnostics.md) на этапе 6 не
реализуется: renderer читает дефолтный шаблон из `DiagnosticCode`. При появлении
i18n/override bundle renderer можно перевести на интерфейс резолвера без
изменения producer-кода.

## Начальный каталог кодов

Каталог должен быть небольшим, но покрывать реальные зоны pipeline. Не нужно
заводить десятки speculative-кодов до интеграции.

| Группа | Стартовые коды |
|---|---|
| `ConfigDiagnosticCodes` | `CONFIG.INVALID_PROPERTY`, `CONFIG.UNKNOWN_POLICY` |
| `SourceDiagnosticCodes` | `SOURCE.READ_FAILED`, `SOURCE.UNSUPPORTED_FORMAT`, `SOURCE.EMPTY_TEXT` |
| `ExtractionDiagnosticCodes` | `EXTRACTION.PATTERN_INVALID`, `EXTRACTION.INDICATOR_SKIPPED`, `EXTRACTION.AMBIGUOUS_VALUE` |
| `ClassificationDiagnosticCodes` | `CLASSIFY.AMBIGUOUS_MATCH`, `CLASSIFY.UNSUPPORTED_INDICATOR_TYPE` |
| `SinkDiagnosticCodes` | `SINK.WRITE_FAILED`, `SINK.ROW_MAPPING_FAILED` |
| `PipelineDiagnosticCodes` | `PIPELINE.STAGE_FAILED`, `PIPELINE.ITEM_SKIPPED` |

Добавление кода требует:

1. запись в enum-каталоге;
2. default severity;
3. default message template;
4. тест уникальности id/messageKey;
5. регистрацию группы в `DiagnosticCatalogs.all()`;
6. обновлённый `docs/diagnostic-catalog.md` через генератор.

## Порядок реализации

1. Создать `com.iocextractor.diagnostics` и README/package-info для корневого
   пакета.
2. Реализовать `DiagnosticSeverity` (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`,
   `FATAL`), `DiagnosticCategory`, `DiagnosticCode`, `Diagnostic`, factory/builder
   на `Clock` и `DiagnosticException`.
3. Добавить unit-тесты модели: immutable context, default severity, override
   severity, validation of nulls, cause/timestamp semantics.
4. Реализовать первые enum-каталоги в `diagnostics.codes`.
5. Добавить `DiagnosticCatalog`/`DiagnosticCatalogs`, которые перечисляют все
   коды без reflection-heavy магии.
6. Добавить тесты каталога: уникальность `id`, уникальность `messageKey`,
   непустые templates, соответствие префикса id группе.
7. Реализовать `Result<T>`, `Notification`, `FailurePolicy` и
   `FailureDecision`.
8. Покрыть collect-and-continue/fail-fast сценарии тестами.
9. Реализовать `DiagnosticSink`, `NoopDiagnosticSink`,
   `CollectingDiagnosticSink`.
10. Реализовать `DiagnosticRenderer` и `TemplateDiagnosticRenderer`.
11. Добавить генератор markdown-карты ошибок,
    `docs/diagnostic-catalog.md` и ссылку на него в `docs/README.md`.
12. Расширить `ArchitectureTest`: `diagnostics..` не зависит от Spring,
    Logback/SLF4J, `adapter..`, `bootstrap..`; `domain..` не зависит от
    `diagnostics.sink..`/`diagnostics.render..`.
13. Расширить тестовый и CI-контур требованиями из раздела ниже.
14. Запустить локальный pre-merge набор проверок.
15. Проверить `git diff --check`.

## Интеграция с текущим кодом

На этапе 6 интеграция должна быть минимальной:

- не переписывать `IocExtractionService` на `Result<T>` до этапа 7;
- не заменять все текущие `throw new IocExtractorException(...)` механически;
- разрешено точечно добавить `DiagnosticException` там, где есть явный
  фатальный diagnostic-код и это не меняет внешний контракт;
- если интеграция вызывает изменение CLI/output поведения, её нужно вынести в
  этап 7.

Такой порядок снижает риск: сначала появляется проверенное ядро diagnostics,
потом pipeline начинает использовать его как контракт между стадиями.

## Тестовая стратегия

Обязательные тесты:

- `DiagnosticTest` - builder, invariants, immutable context, severity fallback,
  equality по `code` + `context`, без `severity`/`cause`/`timestamp`;
- `DiagnosticCatalogTest` - уникальность и полнота каталога;
- `DiagnosticCatalogDocumentationTest` - generated markdown совпадает с
  `docs/diagnostic-catalog.md`;
- `ResultTest` - value+diagnostics, `map`, error/fatal flags, null value
  semantics and null-safe `map`;
- `NotificationTest` - accumulation, conversion to result, clear boundaries,
  per-scope/non-thread-safe contract;
- `FailurePolicyTest` - pure decision for fail-fast vs collect-and-continue and
  throwing only through `Notification.throwIfRejected(policy)`;
- `CollectingDiagnosticSinkTest` - накопление без наружных side effects;
- `TemplateDiagnosticRendererTest` - template placeholders and missing values;
- `ArchitectureTest` - запрет framework/logging/adapter dependencies в core.

Тесты не читают `source/*`, `dataframe/*` и production `application.yml`.

## Тестовый и CI-контур

Цель контура - не только подтвердить текущую реализацию diagnostics, но и
зафиксировать guardrails, чтобы следующие этапы не смешали diagnostics с
logging/pipeline и не сломали каталог кодов.

### Локальный контур разработки

Обязательный набор перед коммитом этапа 6:

```bash
./mvnw test
git diff --check
```

`./mvnw test` должен включать:

- unit-тесты immutable-модели diagnostics;
- unit-тесты `Result`/`Notification`/`FailurePolicy`;
- unit-тесты renderer/sink без файловой системы и без логгера;
- catalog-тесты уникальности кодов и message keys;
- тест синхронизации `docs/diagnostic-catalog.md` с generated markdown;
- ArchUnit-тесты границ diagnostics.

Если этап 6 всё же затрагивает application/bootstrap wiring, локально
дополнительно запускается:

```bash
./mvnw verify
```

### CI-контур

Текущий CI (`.github/workflows/ci.yml`) запускается на `push` в `main` и на
`pull_request`:

```bash
./mvnw -B -ntp verify
```

Этап 6 должен оставаться совместимым с этим контуром: все новые проверки должны
входить в стандартный Maven lifecycle и не требовать локальных файлов,
production-данных, сетевого доступа или переменных окружения.

### Проверки границ

В `ArchitectureTest` добавляются правила:

- `..diagnostics..` не зависит от `org.springframework..`, `org.slf4j..`,
  `ch.qos.logback..`, `..adapter..`, `..bootstrap..`;
- `..diagnostics..` не зависит от `..application.service..`;
- `..domain..` не зависит от `..diagnostics.sink..` и
  `..diagnostics.render..`;
- все enum/classes, реализующие `DiagnosticCode`, зарегистрированы в
  `DiagnosticCatalogs.all()`;
- `LoggingDiagnosticSink` отсутствует до этапа 8.

Допустимые зависимости:

- producer-код может зависеть от `Diagnostic`, `DiagnosticCode`,
  `DiagnosticException`, `Result`, `Notification`;
- application orchestration может зависеть от `FailurePolicy` и
  `DiagnosticSink` как портов;
- adapter/bootstrap wiring может подключать concrete sinks позже, но не в
  этапе 6.

### Проверки каталога

Каталог кодов считается частью публичного контракта. CI должен падать, если:

- два кода имеют одинаковый `id`;
- два кода имеют одинаковый `messageKey`;
- `id` пустой или не соответствует формату `GROUP.CODE`;
- `defaultMessageTemplate()` пустой;
- реализация `DiagnosticCode` не попала в `DiagnosticCatalogs.all()`;
- `docs/diagnostic-catalog.md` не совпадает с generated markdown.

Удаление или переименование диагностического кода после публикации считается
breaking change. Если код больше не используется, он помечается как deprecated в
каталоге/карте ошибок, но id не переиспользуется.

### Проверки отсутствия side effects

Тесты stage 6 не должны:

- читать `source/*`, `dataframe/*`, `configs/*` и production
  `src/main/resources/application.yml`;
- писать в `dataframe/`, `source/`, `docs/` во время теста;
- требовать текущего времени без управляемого `Clock`;
- проверять реальные логи или ECS-ключи;
- запускать полный extraction-прогон как условие unit-тестов diagnostics.

Генератор карты ошибок в тесте генерирует markdown в памяти. Обновление
`docs/diagnostic-catalog.md` делается разработчиком как обычное изменение файла,
а тест только сравнивает фактический документ с ожидаемым.

Сравнение документа делается по стабильному пути `docs/diagnostic-catalog.md` с
нормализацией переводов строк (`\r\n` -> `\n`) и финальных пробелов/перевода
строки (`stripTrailing()`), чтобы CRLF или trailing newline не давали ложных
падений. При создании файла он добавляется в индекс [docs/README.md](../README.md)
как генерируемая карта ошибок.

### Контур регрессии поведения

Если этап 6 остаётся инфраструктурным и не меняет pipeline, `GoldenPipelineTest`
должен проходить без изменения golden-файлов.

Если появляется точечная интеграция `DiagnosticException` на границе adapter или
application, добавляется отдельный synthetic-тест этой ветки. Менять golden
артефакты extraction ради diagnostics нельзя: это признак, что изменение
попало в этап 7.

## Критерии приёмки

- `./mvnw test` зелёный;
- `./mvnw -B -ntp verify` зелёный локально или в CI;
- все публичные типы и порты имеют Javadoc на английском;
- каталог кодов перечислим и тестами защищён от дублей;
- все реализации `DiagnosticCode` зарегистрированы в `DiagnosticCatalogs.all()`;
- `docs/diagnostic-catalog.md` генерируется из каталога и проверяется тестом;
- `Diagnostic` создаётся через управляемый `Clock`, equality не зависит от
  `timestamp`/`cause`;
- `FailurePolicy` чисто вычисляет `fail-fast`/`collect-and-continue`, а
  исключение бросается только на orchestration boundary;
- diagnostics core не импортирует Spring, SLF4J/Logback, adapters, bootstrap;
- в коде этапа 6 нет ECS/MDC/`LoggingDiagnosticSink`;
- существующий pipeline/output не меняется без явной необходимости.

## Риски и решения

| Риск | Решение |
|---|---|
| Diagnostics начнёт смешиваться с logging | Не создавать `LoggingDiagnosticSink`; запретить SLF4J/Logback в `diagnostics..` через ArchUnit |
| `Result<T>` разрастётся в самодельный framework | Оставить только value + diagnostics + минимальные helpers |
| Каталог станет слишком большим до реальной интеграции | Добавить только стартовые коды, новые коды вводить при первом producer-е |
| Новую группу кодов забыли зарегистрировать в агрегаторе | ArchUnit/юнит-guardrail сравнивает все implementations `DiagnosticCode` с `DiagnosticCatalogs.all()` |
| `Throwable`/`timestamp` внутри `Diagnostic` усложнят дедупликацию и сериализацию | Держать cause опциональным; `cause`, `severity` и `timestamp` не участвуют в equality/каталоге |
| Документ карты ошибок устареет | Тест сравнивает generated markdown с committed `docs/diagnostic-catalog.md` с нормализацией переводов строк |

## Проверка после реализации

```bash
./mvnw test
./mvnw -B -ntp verify
git diff --check
```

Опционально после интеграционных касаний:

```bash
./mvnw -q -DskipTests package
java -jar target/ioc-extractor-0.1.0-SNAPSHOT.jar extract --source source/ioc-source.htm --dry-run
```
