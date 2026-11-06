---
title: "DOC-02G — Диагностика, logging и ECS"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02G — Диагностика, logging и ECS

Проверка 2026-11-06 (Asia/Irkutsk), HEAD
`db1e5f38b3d585ad3359f646a432b7374172c1cb`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: dev/observability и library guides diagnostics, logging-slf4j,
logging-slf4j-boot — четыре EN/RU-пары. Capability переработан в English canonical
с новым RU-переводом. Библиотечные руководства в основном уже соответствовали коду;
сохранены примеры и точные гарантии, добавлены пояснения к пределам и интеграции.
Применены code-documenter, java-code-review, cli-developer, spring-boot-engineer.
Полный аудит всех публичных API/README и root security docs не объявляется.
Production/test Java, POM, scripts, generated catalogs и quality policy не менялись.

На входе незакоммиченный DOC-02F. Snapshot включает 372 Markdown/TSV paths,
включая его новые файлы. Его substantive docs/audit и KNOWN-ISSUES с OPS-11/12
сохранены побайтово. Общие navigation/inventory/status дополнены G без сброса
review states других комплектов. Нового коммита нет.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-O01` | M: «delta один раз» могло читаться как доставка каждой observed occurrence | Разделены stage append-only delta, newly retained delivery, replacement и terminal marker. Логическая доставка не приравнена к durable log delivery |
| `DOC-O02` | M: budget не объяснял service-level выбор и оставшиеся resource bounds | Stage/run samples и exact counts связаны со stopping signals; OPERATION, context size, время и число delivery calls не ограничиваются sample limit |
| `DOC-O03` | M: completion/failure policy и CLI outcome оставались неявно связанными | Добавлена таблица severity/policy, full-summary completion и exit 3 для returned COMPLETED_WITH_ERRORS; stopped run, partial durable effects и command failure отделены |
| `DOC-O04` | M: «non-throwing observation» выглядело общей гарантией | Уточнены конкретные wrappers и RuntimeException containment; Error, raw callbacks/provider/scopes не объявлены безусловно безопасными. Retrying a partial batch не является rollback |
| `DOC-O05` | M: redaction context могла восприниматься как очистка Throwable и любых secrets | Описаны registered keys, embedded copies, уровни, lexical URL limits и неизменённый cause в LoggingDiagnosticSink. Message, causes/suppressed и fallback output остаются producer disclosure boundary |
| `DOC-O06` | M: runtime daemon mode связывался с гарантированной загрузкой ECS appender | Разделены ioc.runtime.mode и observability/profile selection; source config определяет console/rolling destination. Рецепт использует file.name, IOC — file.path |
| `DOC-O07` | M: строгие rejection guards Boot sample можно было перенести на IOC encoder | Указана фактическая delegation non-mutable/explicit-dataset веток IOC encoder; recipe-qualified failures не объявлены guards reusable JAR или любого service encoder |
| `DOC-O08` | M: MDC omission и masking не отделялись от точного same-key collision | Объяснены null local vs ambient value, explicit hide, prefix/reserved-path responsibility и отсутствие global registry/context transfer |
| `DOC-O09` | M: logger return, encoder acceptance и persisted bytes могли выглядеть одним подтверждением | Добавлены четыре наблюдаемые стадии; отсутствие log record не разрешает повтор committed mutation и не доказывает rollback |
| `DOC-O10` | M: health overview не включал несколько текущих contributors и nullable read limitation | Добавлена карта ingestion/capacity/lifecycle/import/sync/SMB с owning docs; OPS-10 сохранён как observation failure, UP не объявлен per-source receipt |
| `DOC-O11` | L: service capability оставался Russian canonical без RU path | Полная EN/RU-пара; сохранены JSON query migration, machine keys, metrics labels и import disclosure contracts, обновлены источники и навигация |
| `DOC-O12` | L: RU logging не имел части ссылок на опубликованный артефакт/владение | Восстановлены Central/module references; corresponding translated heading anchor проверен отдельно, executable blocks не менялись |
| `DOC-O13` | M: source/test evidence могло подменить published API и repository consumption | Проверены release manifests и byte equality 22/12 Java sources из original published sources JAR. Три cold Central consumers квалифицировали реальные Markdown examples и backend bytes |
| `DOC-O14` | M: blanket ECS/provider/load claims шире существующей квалификации | Сохранены scalar subset, exact backend baseline, sync mutable-event scope, async/provider/collector/ES/load exclusions и обязанность проверять output/status |

## Живые источники

Source sets `observability`, `library:diagnostics`, `library:logging-slf4j`
дополнены точными entry points и проверками в [source-sets.tsv](source-sets.tsv).

| Граница | Владелец | Что проверено / предел |
|---|---|---|
| Values, counts, policy, delivery | platform-diagnostics-api, stage/run accumulators, sinks | Stable code equality, shallow ownership, exact counts, replacement, marker, pure policy и partial callback failure; API уже подробно описан в guide |
| IOC code/render/bridge | platform-diagnostics, platform-diagnostics-logging, AppConfig | Generic code fallback без ambient category, severity mapping, context-only redaction и неизменённый Throwable; mapping preparer формирует value diagnostic без передачи исходного cause |
| Pipeline enforcement/completion | PipelineRunner, CompletionStatus, ExtractCommand | Stage delta, retained delivery, policy checkpoint, suppression terminal, full-summary completion, per-artifact partial effects и exit 3 |
| Typed logs/MDC | Shared logging API и IOC facade | Scalar validation до level filtering, null declaration, exact-key masking, thread/LIFO ownership, provider cleanup, explicit masks; глобальная schema не гарантируется |
| Boot/service backend | logback-spring.xml, IocEcsStructuredLogEncoder, LoggingBootRecipeIT | Nested shape/native scalars, appender status, dataset/error conflicts и различия service/strict recipe; current source остаётся на pin 4.0.8 |
| Runtime producers/health | LoggingControlEventObserver, import/lifecycle/tracer adapters и contributors | Stable keys/query migration, double TRACE gate, safe metrics labels и durable checkpoints; health не подменяет transaction evidence |
| Published examples | Release records, original signed bundles, actual Central consumers | Main/POM/sources/Javadoc origin/hash; не новый релиз, подпись или GitHub qualification |

[Boot logging reference](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html)
и pinned [ECS event](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/event.yml),
[file](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/file.yml),
[error](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/error.yml)
использованы как upstream context. Онлайн reference ветки Boot 4.0 на момент
чтения показывает 4.0.9; это не меняет tested 4.0.8 и не квалифицирует обновление.

## Проверка текущего source и примеров

Логи/fixtures — ignored `.dev/r030-doc-02g/`.

- `make context`, `make doctor-core`: HEAD/branch, dirty F input и JDK 21 подтверждены.
- Выбранный Surefire cohort: **294 tests / 31 suites, 0 failures/errors/skips**;
  `unit.log`. Diagnostic values/policy/budgets/sinks/renderer/bridge, pipeline runner,
  typed logs, MDC/provider cleanup, redaction, catalogs и selected producer observers.
- Выбранный Failsafe cohort: **14 tests / 3 suites, 0 failures/errors/skips**;
  `integration.log`: LogbackConfigurationIT (2), LoggingMigrationIT (5),
  LoggingBootRecipeIT (7). Проверены actual service JSON/query fixture, реальные
  migration producer branches и strict recipe (5 supported records / 6 rejected scenarios).
- Дополнительно **12 checks / 4 suites, PASS**: DocumentationConventionTest (1),
  ConfigurationDocumentationContractTest (7), ExtractCommandTest (3),
  ResilientDataframeImportObserverTest (1); `documentation-contract.log`.
- `make library-publication-test`: **228 offline tests, PASS**. Extraction,
  bilingual Java/XML/YAML blocks и publication/recovery contracts; никаких publish
  side effects или remote environment approvals этот запуск не выполняет.
- **10 реальных jq calls** из таблицы: five flat queries на явном synthetic
  compatibility fixture и five nested queries на байтах service encoder,
  сохранённых LogbackConfigurationIT. Проверены независимые expected values;
  оба JSON-примера валидны. `examples.json` и `check-examples.py`.
- Все Java/XML/YAML blocks шести library guide files побайтово равны исходным
  qualified inputs: изменения поясняющие. Четыре пары совпадают по heading levels,
  fenced blocks, inline identifiers и normalized link targets; RU heading anchor
  logging guide сопоставлен соответствующему EN anchor, не заменён неверным URL.

## Published-byte verification и Central consumers

Копии исходных signed bundles размещены только в G sandbox. Original bundle,
signed bytes, tags и releases не изменялись. Manifest SHA-256 совпал с permanent
release record:

- Diagnostics: `1a2b7e96eadb62cb4be62d9b22b748728ca95f242c1b5043d84c82ff2908936b`.
- Logging: `36d37dac0a91454c7e306003d433aa8da912040166ebd99b22780b2876020ca0`.

Из sources JAR проверены **22 diagnostics Java files** и **12 logging Java files**:
байты совпадают с текущим source. Tag diff также не показывает Java drift;
у diagnostics изменялась только README navigation, не API implementation.
Это проверка выбранного baseline, не универсальный автоматический API diff TOOL-6.

Фактически выполнены `make library-consumer` с соответствующим компонентом,
G copy qualified bundle и `LIBRARY_REPOSITORY=central`:

1. `diagnostics-consumer`: три фактических Java-примера guide, independent result,
   bounded-count и delivery assertions, чистый cache.
2. `logging-consumer`: три фактических Java-примера, provider/MDC/scalar/masking
   assertions и отказы missing/unavailable SLF4J API.
3. `logging-boot-consumer`: фактические Java/YAML/XML, neutral driver и пять JSON
   records с duplicate-key/type/status assertions, backend разрешён отдельно.

Все три прошли. Каждый разрешил четыре artifacts (main JAR, consumer POM, sources,
Javadoc) выбранного компонента из Central с проверкой origin/hashes против bundle.
Полный IOC bootstrap не попал в независимые consumer projects. Логи
`diagnostics-consumer.log`, `logging-consumers.log` содержат итоговую квалификацию.
В этот раз GitHub Packages не запрашивался; прежние GitHub results остаются в
permanent records. Новой криптографической signing/publication операции не было.

## Итог и границы

Inventory: **364 Markdown paths / 64 source sets**, 56 отсутствующих RU-пар:
root 3, capability 2, ADR 49, guide 2. Исходный baseline 329 paths не меняется;
current/base hashes и preservation checks проверены. **360 входных docs/reference
files побайтово сохранены**, non-G inventory/source states неизменны. `make docs`:
**2764 links / 676 unique / 2531 OK / 0 errors / 233 excluded**.
`git diff --check`: PASS.

R030-DOC, security и общий release readiness остаются открыты. Полный аудит root
SECURITY-ENGINEERING/THREAT-MODEL, всех producer disclosure branches, deployed
second service, live runtime/transport, collector/Elasticsearch mapping и load не
выполнялся. Описание ограничений renderer/cause не является общей security
qualification. Scope существующего SEC-LOG-1 подлежит отдельной сверке root policy.

OPS-11/12 и остальные F defects/open obligations не закрыты; KNOWN-ISSUES сохранён.
Новых production fixes нет. Полные verify/PMD не запускались: их последний PASS
относится к `191e74146dddeeab36bf59d9d09020a92bef0620`, freshness flags false.
Ни выбранные tests, ни cold consumers не обновляют эти markers и не закрывают
TOOL-6/9. Изменения готовы для review без нового коммита.
