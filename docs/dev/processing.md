# Обработка IOC: document → canonical artifacts

Способность читает документ, refang-ит и извлекает IOC, атрибутирует источник,
классифицирует сетевые значения и готовит строки canonical artifacts. Она не
владеет обнаружением файлов, долговременным lifecycle источника, физической
схемой SQLite или доставкой export slices.

Каждый документ обрабатывается через обязательный именованный Router-план.
Поставляемый `original-document` сохраняет полные исходные значения и использует
`observation-selection: retained-observations`: для `keep-first` готовятся только
исходные наблюдения, разрешённые `ioc.pipeline.deduplicate`, без объединения
mapped-коллизий. Это сохраняет резервирование ID и учёт provenance.
`last-nonempty` рассматривает все occurrences и удерживает последнюю целую строку
с непустым selection field по конечному ключу артефакта. Режим `final-key` также
выбирает победителей `keep-first` по конечному ключу и подходит для сведения URL
к host/IP. Оба режима исполняют один Router; прежних стадий dispatch нет.
Диагностики mapping сохраняются для участвующих наблюдений. Lifecycle writer
по-прежнему отклоняет подтверждение с повторными конечными ключами до
резервирования ID и commit; план с `retained-observations` не скрывает эту ошибку.
Основание перехода: [ADR 0034](../ADR/0034-required-router-processing-plans.md).

Подготовка документа использует принадлежащий extraction invocation приватный
SQLite workspace. Каждый occurrence проходит Router и validation; losing
candidates также сохраняются. Глобальный выбор сравнивает полную каноническую
идентичность, сохраняет порядок первого ключа и целую строку победителя.
Все артефакты sealing завершают до failure-policy checkpoint; ID до него не
резервируются. `ArtifactWritePlan` содержит повторно читаемый `RowSource`, а
writer и receipts читают его через закрываемый `RowCursor` без общего списка
победителей. Один workspace/cache обслуживает все артефакты; лимиты находятся
в `ioc.processing.workspace`. Это не ограничивает буферы исходного текста и
извлечённых occurrences. Состояния, pins и recovery:
[ADR 0036](../ADR/0036-sealed-document-preparation-workspace.md).

Document plan открывает отдельную `DocumentProcessingSession` на
подготовку документа; stage закрывает её до checkpoint и при исключениях.
Чистый `IndicatorProcessingSession` из `ioc-processing` переиспользует только
успешные семантические результаты. Ключ классификации включает value, type,
source label и section; обёртки классификатора должны ссылаться на один и тот
же экземпляр политики. Для иной operation policy вычисление остаётся без кеша.
Host-пара сохраняет лишь значение и тип; source присоединяется заново.
Позиции, диагностики, mapper, часы и строки артефактов не кешируются.
Общий лимит — 256 записей и 1 MiB расчётного удержания, максимум 64 KiB на
запись. Бюджет поровну разделён между классификацией и выделением host.
Заполненная часть вытесняет наименее недавно использованную запись; слишком
большая запись вычисляется без кеширования и без вытеснения. Это не потолок
памяти процесса. Классификация исходных и производных значений использует общую
LRU-часть, поэтому часто используемые host остаются при потоке уникальных URL.
Импорт открывает сессию на попытку staging после проверки pinned contract и
закрывает до seal, включая ошибки чтения, mapping и append. Все строки проходят
прежние проверки authority и staging. Повторная попытка получает новую сессию;
receipt recovery и as-is импорт её не открывают. Прямой вызов preparer одной
строки и advisory validation сохраняют сессию строки. Решение:
[ADR 0033](../ADR/0033-bounded-semantic-reuse-during-import-staging.md).

Имена и аргументы transforms в `ConfigurableRowMapper` привязываются один раз
при создании mapper. Порядок вызовов, gates и ошибки достигнутых колонок
сохраняются; значения провайдеров и строки продолжают вычисляться для каждого
occurrence. Проверка SHA-256 в `CanonicalKeyMaterial` использует точный проход
по 64 строчным ASCII hex-символам без компиляции regex на каждом ключе.

Статические разрешения выбранного processed-import preparer проверяются один раз
при его создании: набор артефактов, output targets, запрет изменения source и
эффективные merge policies закреплены за полным immutable compiled contract.
На каждой строке проверяются соответствие закреплённому контракту, наличие
admitted branches/cells и все прежние правила source authority и сборки carriers.
Один ID контракта не разрешает подмену definition, version или fingerprint.


## Квалификация стоимости

Атрибуция сохраняет порядок occurrences и выбирает ближайший маркер с позицией
не больше позиции IOC. Для упорядоченных occurrences поиск выполняется одним
курсором (`O(N+S)`); для произвольного порядка — бинарным поиском по маркерам
(`O(N log S)`). Обнаружение и приоритет перекрывающихся маркеров общие для обоих
путей. Подсчёт IOC без источника использует attribution decisions и не создаёт
дополнительный полный список `Indicator`.

Лимит `ioc.pipeline.max-diagnostics-per-run` действует внутри циклов extraction
и document preparation. Каждый stage-local collector удерживает первые
ELEMENT/RUN occurrences до лимита и первые ERROR/FATAL сверх него, сохраняя
порядок поступления. Точные counts по severity передаются через `DiagnosticBatch`
и `Envelope` отдельно от details. Runner применяет общий лимит, сохраняет
прежние samples и доставку retained occurrences exactly once, добавляя одно
итоговое `PIPELINE.DIAGNOSTICS_SUPPRESSED`. Это итоговое сообщение не входит в
observed counts. OPERATION diagnostics остаются вне occurrence-бюджета по
прежнему контракту. Отказ collector/delivery не скрывается; failure policy
видит поздние ERROR/FATAL до резервирования ID и canonical write. TRACE остаётся
отдельной явно включаемой доставкой решений, а не хранилищем diagnostic details.

Исторический `make processing-route-comparison` сравнивал совместимый и выбранный
пути через штатную Spring-конфигурацию в отдельных JVM/SQLite/workspaces. После
полного перехода текущий код поддерживает только выбранные маршруты;
`--selected-only` остаётся способом сравнивать закреплённые версии одного плана. Сравнение включает
выходные поля/ключи, canonical IDs, source occurrences, import COALESCE и receipt.
Время относится к обработке после старта контекста, allocations — к вызывающему
потоку; heap и текущий RSS снимаются с интервалом 10 ms. Первое выполнение и
прогрев с новыми ключами измеряются отдельно. Профили и диагностический режим:
[developer tools](../../tools/dev/README.md).

Обработка каждого occurrence остаётся частью выбранной политики. Уменьшение
числа вычислений классификации и удержание одного победителя не устраняют
mapping и transient allocations. Квалификация от 2026-10-03 обнаружила превышение
исторической границы allocations на документе с 100k occurrences/20 ключами и
sampled heap на документах с 50% повторов либо без повторов. Принятие ресурсного
бюджета остаётся открытым — [OUT-4](../KNOWN-ISSUES.md). Neutral Router с четырьмя
callers и отдельная калибровка сессий не задают throughput/heap SLO всего сервиса.

Mixed/long comparison сохраняет исходное представление ради одинаковых полей;
он не измеряет очистку подробных URL/IP. Отдельный `--shape host-collapse
--selected-only` квалифицирует HTTP/HTTPS URL с портами, путями, query и fragment,
которые сходятся к небольшому набору domain/IP. Он использует host-ветвь и
одинаковую policy до/после оптимизации, проверяет конечные поля/ключи, победителей,
диагностику и provenance. Ресурсная приёмка остаётся отдельной; множество разных
URL заполняет bounded session. LRU позволяет переиспользовать соседние повторы
и частые производные host, но не удерживает все уникальные исходные URL.
`make processing-optimization-comparison` сравнивает два замороженных runtime
выбранного пути с чередованием JVM до/после. Он проверяет полную подпись результата,
включая сводку диагностик; тексты и контекст отдельных диагностик в подпись не
входят. Локальная серия из пяти пар на профиль после изменения кеша, сессии
импорта, проверки ключей и привязки transforms показала на 100k URL occurrences
снижение времени на 11,3% и allocations вызывающего потока на 16,4%. Sampled peak
heap вырос на 4,8%, поэтому это не доказательство снижения всей памяти процесса
и не закрытие OUT-4.

Исторический совместимый import отключал `processed-route`, сохраняя каталог
планов и idle Camel. Его startup/RSS показывал стоимость выбора пути, а не
разницу deployments без Camel и с Camel. Такая конфигурация после полного
перехода больше не допускается.

## Runtime flow

Порядок стадий является частью application contract и собирается явно:

```text
read -> refang -> extract -> attribute
     -> required Router plan -> observation selection -> failure-policy checkpoint
     -> canonical commit -> mutable CSV projection
```


`adapter-processing-camel` компилирует ограниченный план: проверяет ссылки,
связывает зарегистрированные предикаты, создаёт локальные маршруты для операций,
веток и последовательной отправки выбранным назначениям. Выбор FIRST/ALL/
EXCLUSIVE учитывает недоступные представления и выполняется до отправки;
повторной проверки условий в Camel нет. Представления, затребованные условиями,
вычисляются по мере необходимости не чаще одного раза за вызов. Технический
контракт R3 различает доступное, отсутствующее и ожидаемо недоступное значение;
явное восстановление допускает только зарегистрированные причины и отдельное
альтернативное представление. Результат сохраняет причины, фактически
затронутые ветки и исход каждой выбранной ветки: кандидат, фильтрация либо
ожидаемый отказ. Представления, нужные только для mapping, вычисляются лишь
после выбора соответствующей ветки. R4 добавил изолированный lifecycle Camel:
bootstrap требует зарегистрированные планы и запускает общий runtime,
проверяет готовность маршрутов и ограничивает время остановки с ожиданием
активных вызовов. Типизированные события выбора, вычисления представлений,
восстановления и исполнения веток переводятся в существующий application
tracer с fingerprint политики, но без значений IOC; MDC-контекст плана,
представления и ветки
восстанавливает родительские поля даже при ошибке. Диагностика и её итоговая
серьёзность остаются у application, а Camel не делает повторных попыток.
После атрибуции источника документ всегда использует
`DocumentProcessingAdapter`: каждый occurrence передаётся в Router, выбранные
ветки используют CSV mapper и представления для отдельных колонок.
`PrepareRoutedArtifactsStage` применяет `observation-selection` плана:
`retained-observations` сохраняет допущенные исходные строки для KEEP_FIRST,
а `final-key` выбирает победителей по ключам заполненных строк.
LAST_NONEMPTY в обоих случаях видит все occurrences и выбирает целую строку по
конечному ключу. Пустые планы для явно пропущенных артефактов сохраняют контракт
количества записей lifecycle receipt. `IocExtractionServiceFactory` требует
`ioc.processing.document-plan` и привязывает к Router preparer конкретного
запуска с его `_source_key`. Каждый processed-контракт требует свой явный
`processed-route`; режим `as-is` не вызывает processed preparation.
Оценка серьёзности диагностик, правила IOC и canonical-запись сохраняют текущих
владельцев. Именованные планы, выбор документа и привязки processed-контрактов
обязательны; отсутствие выбора является ошибкой конфигурации. На старте
`ProcessingPlanCatalog` проверяет ссылки на активные артефакты, представления,
колонки, классификации и предикаты, форму условий AND/OR/NOT, типизированные
аргументы `type-in` и явный список `omitted-artifacts` для каждого включённого,
но не маршрутизируемого артефакта документа. Компиляция создаёт технический
дескриптор Router и привязки колонок; выбранный план активируется в общем
Camel runtime до начала обработки.
`ConfigurableRowMapper` умеет брать отдельное классифицированное представление
для каждой колонки: её gate, provider и transforms используют одно и то же
представление. Контекстные `id` и `source.label` нельзя переназначить через
`field-views`; привязка колонки с `const` без условий допускается, но не требует
вычисления представления и не меняет константу. При выполнении документа
ожидаемый отказ представления получает итоговую диагностику по фактически
достигнутым потребителям: только успешные восстановления дают WARN, хотя бы
один невосстановленный потребитель — ERROR. Отклонение маршрута и
неоднозначный EXCLUSIVE тоже дают ERROR до checkpoint; штатный SKIP не
считается ошибкой.

`platform-etl` даёт framework-free `Envelope`, `Stage`, `Pipeline` и
`PipelineRunner`. IOC-specific payloads и порядок стадий принадлежат
`core/ioc-application`; композиция классификации и декларативного заполнения
полей — в чистом `core/ioc-processing`, доменные правила — в `core/ioc-domain`;
Tika, RE2/J, Guava PSL и Commons CSV изолированы адаптерами.

Для сетевых значений доменный `NetworkAddressParser` задаёт единую поддерживаемую
форму адреса для признаков и вывода хоста. Он принимает HTTP(S), DNS или IPv4,
необязательный порт, путь, query и fragment; возвращает типизированную причину
ожидаемого отказа для неподдерживаемой формы. `NetworkHostDeriver` создаёт
отдельный IOC с исходным контекстом источника, не изменяя оригинал.
`ExactIndicatorParser` из `ioc-processing` требует одного совпадения на всю структурированную ячейку,
поэтому частичный адрес не становится импортированным IOC. Документный
экстрактор по-прежнему ищет совпадения внутри текста. Вывод host подключён к
явно выбранному плану документа или processed import.

Технический runtime связывает фиксированные Camel endpoints после старта своего
контекста и использует ссылки внутри этого контекста. Параметры условий
связываются один раз при компиляции leaf; `type-in` получает неизменяемое
множество типов после semantic preflight. Это не кеш результатов IOC:
предикаты и востребованные операции выполняются для каждого вхождения.
Одна доступная ветвь вызывается напрямую после завершения selection и
разрешения всех необходимых views; несколько ветвей используют
последовательный Recipient List с локальным для exchange накоплением.
Тип результата проверяется на каждой branch route, а итоговый список
фиксируется как immutable при завершении; пропущенный ответ является ошибкой.

Порядок стадий не конфигурируется. Декларативны данные правил и mapping, а также
`FailurePolicy`; перестановка стадий является изменением application contract.

## Инварианты

1. **Core остаётся framework-free.** Domain/application не импортируют Spring,
   Tika, RE2/J, Guava, Commons CSV, JDBC или filesystem implementation details;
   границы защищает ArchUnit.
2. **Паттерны совместимы с RE2/J.** Поддерживаемые типы — `IPV4`, `DOMAIN`,
   `URL`, `MD5`, `SHA1`, `SHA256`; patterns используют `\b` и не используют
   look-around/back-references, чтобы работали оба `PatternEngine`.
3. **Поддерживаемый document contract проверяем.** HTML, включая explicit
   legacy charset, PDF, DOCX и XLSX имеют contract tests. Остальные parser-ы
   Tika являются best effort до появления fixture и теста.
4. **Domain возвращает решения, а не telemetry.** Refang, extraction и
   attribution материализуют pure outcomes. Pipeline-neutral application
   `IndicatorClassifier` materializes the same network/file decision for
   ordinary ingest and managed import; callers own diagnostics and gated TRACE
   without repeating the domain rule.
5. **Dedup принадлежит политике плана.** Штатный `retained-observations`
   учитывает `ioc.pipeline.deduplicate` для KEEP_FIRST без схлопывания разных
   исходных IOC в одну строку после mapping. `final-key` сохраняет occurrences
   до mapping и выбирает целые строки по конечному ключу. Classification
   выполняется в требуемом представлении через один Router и общую сессию.
   Durable dedup отдельно выполняет canonical storage по `row_key`.
6. **Mapping не делает IO.** `ArtifactPreparer` применяет `accepts`, filters,
   column providers и transforms. `from: id` остаётся deferred slot до
   materialization непосредственно перед commit. LAST_NONEMPTY выбирает одну
   целую строку с непустым mapped `selection-column` по конечному ключу;
   этот выбор выполняется application после Router. KEEP_FIRST следует
   `observation-selection` плана.
7. **Failure policy применяется до durable write.** Ожидаемый data-dependent
   отказ provider/transform становится `SINK.ROW_MAPPING_FAILED`; неожиданный
   exception остаётся run failure. Rejected run не резервирует id и не пишет
   canonical rows.
8. **Persistence выбирается явным command context.** До activation pipeline
   использует compatibility repository. В `fixed` mode driving boundary
   передаёт `LifecycleWriteContext`; `WriteArtifactsStage` вычисляет row keys из
   уже подготовленных templates и вызывает lifecycle writer с observation и
   receipt facts. Ordered mutable fields дополнительно передают исходную позицию
   и durable registration; обычные artifacts этого metadata не требуют.
   Domain/stages не читают config и не знают JDBC.
9. **Post-commit projection advisory.** Успешный canonical commit необратим для
   текущего pipeline run. Lossy mutable projection может добавить
   `SINK.CHARSET_UNMAPPABLE` и повысить completion до warnings, но не запускает
   повторную failure-policy rejection.
10. **Dry-run не выполняет side effects.** Pipeline проходит read/decision/
   preparation, но пропускает canonical commit и projection.
11. **Processed import переиспользует policy, а не orchestration.**
    `ProcessedImportRowPreparer` применяет ordinary refang/extract/classify и
    declarative artifact mapping к уже структурированной logical row. Он не
    читает source, не владеет staging/transaction и сохраняет compound-row
    grouping; `as-is` этот path не вызывает. Для processed-маршрута входные
    IOC-ячейки и производные выходные колонки указываются явно: из имён
    provider-ов они не выводятся. Application вычисляет record/match keys и
    проверяет форму строки только после обработки. Предупреждения явного восстановления представления отделены от rejection issues и проходят через sealed stage и
    canonical receipt к terminal-отчёту.

Для daemon выбранная политика документа закрепляется в service DB (schema v12).
При изменении fingerprint старт отклоняется, пока есть незавершённые записи
ingestion ledger, файлы в processing или document admissions, ещё не завершённые
либо ожидающие окончательной регистрации.
При неизменном fingerprint восстановление продолжается. Импорт закрепляет
версию и fingerprint контракта на delivery: при повторном staging сначала
сверяется активный контракт и только затем читаются строки; совместимый sealed
stage можно принять без повторного чтения. После canonical commit завершение
всегда идёт по receipt. Изменение политики не переписывает старые canonical
записи; их срок действия определяется отдельной lifecycle-конфигурацией.

## Декларативный artifact mapping

Колонка задаёт `name`, storage `type`, `from`/`const`, optional `when-type` и
ordered transforms. `type` описывает public storage schema и участвует в export
schema fingerprint; это не value provider.

Актуальные provider/transform/predicate keys принадлежат
`ConfigRegistryCatalog` и preflight-ятся до первой обработки. Новый артефакт,
выразимый существующими registries, добавляется конфигурацией. Новая семантика
значения требует тонкого component-а в `ioc-processing` и явной регистрации в
composition root. Новый формат или технология вывода требует отдельного
адаптера за application port.

`ioc.source.charset` относится только к document boundary. Для text/HTML можно
форсировать charset; container formats владеют внутренней кодировкой сами.
`ioc.sink.csv.charset` относится к mutable projection и immutable export, но у
них разные failure contracts: projection допускает replacement + advisory,
immutable slice использует strict encoding, потому что bytes входят в hashes.

Классpath-конфигурация также определяет самостоятельный `ioc_aggregate` без
публичного `id`: `name`, `ip_address`, `url_match`, `host_match`, `hash`.
Маршрутизация carrier-значений выражена `when-type`/`when-types` и структурными
conditions: bare IPv4 попадает в `ip_address`, clean DOMAIN — в `host_match`,
адрес со схемой, путём, query или port — в `url_match`, hashes нормализуются в
upper case. Полная четвёрка carrier-колонок является canonical identity; Java-код
не ветвится по имени этого артефакта. Whole-row `last-nonempty` выбирает последнюю
помеченную occurrence внутри документа, а ordered field policy меняет только
непустой `name` от более поздней durable registration.

Registration создаётся driving boundary до business processing: oneshot
декоратор завершает её в рамках invocation, daemon сохраняет pre-hash recovery в
JDBC либо file journal, managed import связывает её с delivery reservation.
Повтор и restart возобновляют тот же rank. Aggregate начинает пустым: storage
reconciliation создаёт схему, но не строит строки из четырёх прежних artifacts.

## Отказы

| Граница | Поведение |
|---|---|
| Source parsing | adapter переводит parser/IO failure в typed source failure |
| Empty text / dropped item | diagnostic становится частью `Envelope` outcome |
| Expected mapping rejection | element diagnostic до commit; судьбу решает `FailurePolicy` |
| Programming/storage defect | run failure; не маскируется collect-and-continue |
| Projection после commit | hard failure завершает invocation ошибкой, canonical truth сохраняется |

## Как расширять

- Новый `IndicatorType`: обновить domain model, RE2-compatible pattern corpus,
  normalization/classification и artifact routing tests.
- Новый provider/transform/predicate: добавить adapter/domain component,
  зарегистрировать key в `ConfigRegistryCatalog`, обновить preflight и mapper
  contract tests.
- Новый artifact на существующем CSV contract: добавить sink + identity config
  и проверить schema/identity drift.
- Новый sink/format: реализовать application port новым adapter-модулем; stage
  order и domain model не должны зависеть от технологии.

## Источники истины

- Pipeline assembly: `IocExtractionService`.
- Generic execution/outcome: `platform-etl`, `platform-diagnostics` и их tests.
- Domain rules: `core/ioc-domain` + `DomainBoundaryTest`.
- Registry/config contract: `ConfigRegistryCatalog`, `ConfigRegistryPreflight`,
  `application.yml`.
- Source formats: `TikaSourceReaderFormatContractIT` и charset tests.
- Prepare/checkpoint/commit: `StageContractTest`,
  `ArtifactPolicyCheckpointTest`, `TypedMappingFailurePolicyTest`.
- Выбранный план от входного документа до canonical SQLite:
  `CustomerRoutingPipelineIT`; неизменённый путь и полный public-output
  baseline: `GoldenPipelineIT`.
- Generated reference: `DIAGNOSTICS-CATALOG.md`.

## Когда обновлять документ

Обновить при изменении порядка стадий, supported IOC/document contract,
failure-policy checkpoint, mapping DSL, границы canonical commit или
post-commit projection semantics. Переименование внутреннего mapper-а само по
себе обновления не требует.

## Связанные документы

- [storage.md](storage.md) — canonical identity, transaction и projection truth.
- [Руководство по маршрутам](../guides/ioc-processing-routes.md) — включение
  политики, пример конфигурации и восстановление.
- [ingestion.md](ingestion.md) — daemon driving flow.
- [observability.md](observability.md) — diagnostics и gated decision tracing.
- [ADR-0017](../ADR/0017-diagnostics-first-class-outcome.md) — почему write path
  разделён на prepare/checkpoint/commit.
