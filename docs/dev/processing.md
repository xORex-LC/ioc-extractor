# Обработка IOC: document → canonical artifacts

Способность читает документ, refang-ит и извлекает IOC, атрибутирует источник,
классифицирует сетевые значения и готовит строки canonical artifacts. Она не
владеет обнаружением файлов, долговременным lifecycle источника, физической
схемой SQLite или доставкой export slices.

При выборе победителя по конечному ключу routed document stage и
`CsvArtifactPreparer.prepareOccurrences` удерживают только одну целую строку
на ключ, сохраняя порядок первого появления ключей. `last-nonempty` заменяет
строку только при непустом значении выбранной колонки; метаданные позиции
принадлежат той же строке. Все occurrences проходят mapping и диагностику,
включая проигравшие. Совместимый `prepareLegacy` не группирует строки: повторы
и коллизии mapped key сохраняют прежнее резервирование ID и учёт provenance.
Ограничение относится к удержанию кандидатов, а не к памяти всего документа.
Существующий lifecycle writer отклоняет подтверждение с повторными конечными
ключами до резервирования ID и commit. Поэтому legacy mapping-коллизии, в том
числе при отключённой upstream-дедупликации, в этом режиме завершаются ошибкой;
оптимизация не заменяет их неявным объединением строк.

Выбранный document plan открывает отдельную `DocumentProcessingSession` на
подготовку документа; stage закрывает её до checkpoint и при исключениях.
Чистый `IndicatorProcessingSession` из `ioc-processing` переиспользует только
успешные семантические результаты. Ключ классификации включает value, type,
source label и section; обёртки классификатора должны ссылаться на один и тот
же экземпляр политики. Для иной operation policy вычисление остаётся без кеша.
Host-пара сохраняет лишь значение и тип; source присоединяется заново.
Позиции, диагностики, mapper, часы и строки артефактов не кешируются.
Общий лимит — 256 записей и 1 MiB расчётного удержания, максимум 64 KiB на
запись; превышение ведёт к обычному вычислению. Это не потолок памяти процесса.
Импорт открывает такую сессию только на одну логическую строку; delivery-wide
reuse отложено, receipt recovery её не открывает. Решение:
[ADR 0032](../ADR/0032-invocation-owned-ioc-semantic-reuse.md).

Статические разрешения выбранного processed-import preparer проверяются один раз
при его создании: набор артефактов, output targets, запрет изменения source и
эффективные merge policies закреплены за полным immutable compiled contract.
На каждой строке проверяются соответствие закреплённому контракту, наличие
admitted branches/cells и все прежние правила source authority и сборки carriers.
Один ID контракта не разрешает подмену definition, version или fingerprint.


## Квалификация стоимости

`make processing-route-comparison` запускает совместимый и выбранный пути через
штатную Spring-конфигурацию в отдельных JVM/SQLite/workspaces. Сравнение включает
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
он не измеряет очистку подробных URL/IP. Совместимый import отключает выбор
`processed-route`, сохраняя каталог планов: наличие каталога запускает idle Camel
даже без выбранного импортного маршрута. Startup/RSS этого профиля показывает
стоимость выбора пути, а не разницу deployments без Camel и с Camel.

## Runtime flow

Порядок стадий является частью application contract и собирается явно:

```text
read -> refang -> extract -> attribute -> deduplicate(batch-local)
     -> classify -> prepare rows -> failure-policy checkpoint
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
bootstrap запускает runtime только при наличии зарегистрированного плана,
проверяет готовность маршрутов и ограничивает время остановки с ожиданием
активных вызовов. Типизированные события выбора, вычисления представлений,
восстановления и исполнения веток переводятся в существующий application
tracer с fingerprint политики, но без значений IOC; MDC-контекст плана,
представления и ветки
восстанавливает родительские поля даже при ошибке. Диагностика и её итоговая
серьёзность остаются у application, а Camel не делает повторных попыток.
Для документа реализован отдельный исполняемый путь после атрибуции источника:
каждое вхождение передаётся в технический Router через композиционный
`DocumentProcessingAdapter`; выбранные ветки используют общий CSV mapper и
настроенные представления для отдельных колонок. `PrepareRoutedArtifactsStage`
группирует кандидатов по ключу уже заполненной строки и применяет прежний
`ArtifactOccurrenceSelector`. Пустые планы для включённых, но не выбранных
артефактов сохраняют контракт количества записей lifecycle receipt.
`IocExtractionServiceFactory` выбирает этот путь, когда указан
`ioc.processing.document-plan`, и привязывает к Router preparer конкретного
запуска, сохраняя его `_source_key`. Отсутствие выбора оставляет прежний путь.
Контракт processed import может независимо выбрать именованный план через
`processed-route`; `as-is` остаётся прежним. Оценка серьёзности диагностик,
правила IOC и canonical-запись
сохраняют текущих владельцев.

Необязательный `ioc.processing` описывает именованные планы и выбранный
`document-plan`. При отсутствии выбора действует прежняя обработка. На старте
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
5. **Dedup и classification зависят от выбранного пути.** Прежний путь
   устраняет batch-дубликаты до классификации. Маршрутизируемый путь сохраняет
   каждое вхождение до mapping и выбирает победителя по финальному
   артефактному ключу; источник и позиция каждого вхождения сохраняются.
   Durable dedup отдельно выполняет canonical storage по `row_key`.
6. **Mapping не делает IO.** `ArtifactPreparer` применяет `accepts`, filters,
   column providers и transforms и возвращает write plan. `from: id` остаётся
   deferred slot до materialization непосредственно перед commit. Артефакт может
   явно выбрать whole-row `last-nonempty` по mapped column: тогда mapper сначала
   строит кандидата для каждого occurrence, группирует по canonical identity и
   выбирает одну строку целиком. Без этой policy сохраняется прежний путь с
   одной строкой на deduplicated indicator.
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
    grouping; `as-is` этот path не вызывает. Для нового Router-пути входные
    IOC-ячейки и производные выходные колонки указываются явно: из имён
    provider-ов они не выводятся. Application вычисляет record/match keys и
    проверяет форму строки только после обработки. Предупреждения успешного
    fallback отделены от rejection issues и проходят через sealed stage и
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
