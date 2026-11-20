---
title: "R030-QUAL — Code health"
version: "0.3.0"
goal_id: "R030-QUAL"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-QUAL — Аудит качества кода

## Результат и состояние

Согласованный production inventory должен пройти конечный review по поведению,
инвариантам, ресурсам/concurrency, cohesion, dependency/API boundaries и
дублируемым знаниям. Модульные cells [матрицы](../status-matrix.md) остаются
открытыми за исключением шести библиотек: их полный scoped review зачтён
2026-11-17 после сверки исходников с release sources и текущих tests/boundaries.
[Основания и границы зачёта](../../../../.github/release-evidence/ru/0.3.0.md#module-hardening-reconciliation-2026-11-17).
Историческое analyzer triage не закрывает полный review остальных модулей.

Текущие правила — [CONVENTIONS](../../../CONVENTIONS.md),
[ARCHITECTURE](../../../ARCHITECTURE.md), [TESTING](../../../TESTING.md) и
[build quality](../../../dev/build-quality.md). Результаты исходного triage —
[release evidence](../../../../.github/release-evidence/0.3.0.md#build-and-test-adoption).

## Порядок оставшейся работы

Для модуля определить supported behavior, critical risks и ownership; прочитать
owning README/capability/ADR и сверить существующие tests. Findings получают
owner и `fix-now`, `debt`, `accepted` или `not-applicable` с rationale, acceptance
criteria и verification plan. Characterization предшествует рискованному
refactoring, bug fix получает regression test. Structural и behavioral changes
проводятся отдельными срезами; после проверок обновляются docs и module cell.

`QUAL-BOOT-1` переведён из `fix-now` в `deferred` решением пользователя
2026-11-18. Bootstrap reorganization и предложенный structural/technical
refactoring исключены из 0.3.0; причины, owners и условия возврата постоянно
сохранены в [ARCH-6](../../../KNOWN-ISSUES.md#arch-6) и
[CODE-6](../../../KNOWN-ISSUES.md#code-6). Существующие implementations не
объявляются исправленными, а module review и final qualification сохраняются.
`QUAL-REGEX-1` закрыт `TEST-REGEX-03` и behavioral/composition tests;
`QUAL-CSV-1` закрыт актуальным description `adapter-csv/pom.xml`. Эти задачи не
возвращаются в очередь как отсутствующая реализация.

## Сохранённый CPD intake

Это принятые dispositions исходного review. Перед новым срезом проверить
текущие occurrences: изменившийся analyzer count сам по себе не доказывает
ни актуальность проблемы, ни её исправление. Owner — `R030-QUAL`, дополнительные
владельцы указаны в таблице. `debt` требует отдельного выбора среза, а не
автоматического устранения до релиза.

| ID | Scope и disposition | Следующий шаг или условие пересмотра |
|---|---|---|
| `QUAL-CPD-01` | Config-path tokenization/reflection: `fixed`, единая shape-модель | Изменение property-path grammar; build/configuration owner |
| `QUAL-CPD-02` | CLI/daemon extraction diagnostic fields: `debt` | Выбрать законную boundary общего mapping без связи адаптеров наружу |
| `QUAL-CPD-03` | Event metadata → MDC в handlers: `debt` | Review ECS helper boundary с сохранением handler-specific tail fields; `R030-ARCH` |
| `QUAL-CPD-04` | Length-prefixed SHA-256 fingerprints: `fixed`, единый framing | Изменение byte framing или версии fingerprint |
| `QUAL-CPD-05` | Diagnostic enum boilerplate: `accepted`, разные category/impact/message | Третье общее semantic change, доказывающее общую причину изменения |
| `QUAL-CPD-06` | Completed-slice traversal: `fixed`, общий private traversal, разные validators/results | Изменение child-path provenance или symlink policy |
| `QUAL-CPD-07` | SMB connect/auth/password wiping: `fixed`, adapter-local setup, разные returned owners | Изменение authentication/session ownership; `R030-SEC` |
| `QUAL-CPD-08` | Executor shutdown в daemon schedulers: `debt` | Lifecycle/concurrency review механики и разных scheduling states; `R030-ARCH` |
| `QUAL-CPD-09` | Legacy property parsing в file ledger/JDBC importer: `accepted` | Retirement file-ledger migration path; `R030-RETIRE` |
| `QUAL-CPD-10` | Maintenance/slice-retention start/stop: `accepted`, независимые cadence owners | Изменение lifecycle policy либо ещё одно повторение |
| `QUAL-CPD-11` | Import workspace/canonical-promotion SHA-256: `fixed`, package-private algorithm, caller diagnostics | Изменение digest algorithm/version; `R030-BUILD` |
| `QUAL-CPD-12` | CSV header/streaming reader stack: `debt` | Общий local helper допустим после проверки TWR ownership и разных error/result contracts |

Сверка 2026-11-17: текущие 22 группы не совпадают один-к-одному с исходными
12 semantic items. CPD-02/03/05/08/09/10/12 сохраняют dispositions; CPD-08
также охватывает lifecycle/observation schedulers. Исчезновение старой группы
не отменяет semantic debt и не открывает повторно CPD-01/04/06/07/11.
Дополнительные текущие группы имеют следующие решения:

| ID | Scope и disposition | Owner / условие пересмотра |
|---|---|---|
| `QUAL-CPD-13` | `IocProperties` ↔ `DataframeImportCatalogDraft`, пять групп: `accepted`; внешняя binding shape и framework-free draft имеют разные owners | Configuration/import; изменение mapping/validation требует parity tests; не связывать core со Spring ради одинаковых record fields |
| `QUAL-CPD-14` | JDBC schema catalogs/public-header helpers, четыре группы: `debt`; повторяется знание о допустимых schemas и колонках | Storage; adapter-local helper после characterization duplicate/null/order/error contracts и cursor ownership |
| `QUAL-CPD-15` | Два history INSERT в `JdbcLifecycleArchive`: `accepted`; один SQL shape, разные lifecycle close/observation transitions | Storage/lifecycle; изменение history columns требует сверки обоих путей в одной транзакционной regression suite |
| `QUAL-CPD-16` | `requireArtifacts` в projection/history services: `debt`; общий invariant unique nonblank artifact catalog | Application/lifecycle; package-private validation boundary, сохранить order/copy/null/duplicate behavior, не объединять execution/cadence owners |

## Сохранённый PMD intake

Metrics и constructor arity намеренно не фиксируются как текущая истина.
Owner — `R030-QUAL`; `TEST` означает участие `R030-TEST`, `ARCH` — `R030-ARCH`,
`SEC` — `R030-SEC`. Для `accepted` сохранено условие повторного review.

| ID | Scope и disposition | Обязательная граница review / trigger |
|---|---|---|
| `QUAL-PMD-01` | `FileSourceMessageHandler.handle`: `debt` | Характеризовать hashing/ingest retries, durable rejection и terminal diagnostic precedence; TEST |
| `QUAL-PMD-02` | `SmbFileTransport.publish`: `debt` | Сохранить idempotency, temporary upload/verify/replace/cleanup и remote commit protocol; TEST |
| `QUAL-PMD-03` | `ExportService` construction: `debt` | Искать cohesive typed construction model, сохраняя явные ports; ARCH |
| `QUAL-PMD-04` | `IngestionService` construction: `debt` | Группировка collaborators не ослабляет shared-guard ownership; ARCH |
| `QUAL-PMD-05` | `IngestionService.recoverIncomplete`: `debt` | Сначала recovery ordering, per-key/orphan paths и exact failure identity; TEST |
| `QUAL-PMD-06` | Extraction service/pipeline/source-scoped factory: `debt` | Один inward construction model без adapter concerns в core; ARCH |
| `QUAL-PMD-07` | `ArtifactPublishService` saga: `debt` | Декомпозиция только после ledger/idempotency characterization; TEST |
| `QUAL-PMD-08` | `EarlyCliLauncher.commandFactory`: `accepted`, явный registry | Conditional registration policy либо новая копия registry |
| `QUAL-PMD-09` | `CsvProcessedImportRowPreparer.indicators`: `accepted`, читаемые guards | Изменение validation branches или failure aggregation; TEST |
| `QUAL-PMD-10` | `LocalImportTerminalStore.archive`: `debt` | Сначала таблица source/target terminal states, idempotency и ambiguity rejection; TEST |
| `QUAL-PMD-11` | `JdbcCanonicalImportWriter.planBranch`: `debt`, high-risk | Сначала identity/match/merge/lifecycle branch outcomes и transaction invariants; TEST |
| `QUAL-PMD-12` | `SmbExceptionMapper.classify`: `accepted`, ordered classifier | Новые SMB error kinds либо escaped misclassification; SEC |
| `QUAL-PMD-13` | `AppConfig` explicit wiring: `accepted` | Появление cohesive typed construction boundary; ARCH owner |
| `QUAL-PMD-14` | `ManagedDataframeImportRuntime` construction: `debt` | Typed components не скрывают detection/recovery/retention/lifecycle owners; ARCH |
| `QUAL-PMD-15` | Lifecycle activation/reconciliation: `debt` | Сохранить resumable phases, durable checkpoints, bounded reconcile и retry; TEST |
| `QUAL-PMD-16` | `IngestionService.processClaimed`: `debt` | Сначала primary/secondary failures и claim/run/commit/finalization precedence; TEST |

Сверка 2026-11-17: текущие 19 policy findings содержат новые участки, которых
нет в исходном intake. Отсутствующие теперь findings PMD-01/02/04/05/07/09
не означают автоматического закрытия прежних semantic review obligations.
PMD-15 охватывает два метода, PMD-11 — две метрики одного метода.

| ID | Scope и disposition | Owner / обязательная граница review |
|---|---|---|
| `QUAL-PMD-17` | `JdbcCanonicalImportWriter.applyMutations`: `debt` | Storage/TEST; existing/new × ordered/plain outcomes, reserved ID offsets, requested slots, observer failure и атомарность общей транзакции до decomposition |
| `QUAL-PMD-18` | `SmbChangeNotifyWatcher.currentPending`: `accepted` | SMB/concurrency; volatile field читается `close()` для cancellation; local UnusedAssignment не разрешает удалять publication/reset; revisit при смене stop protocol |
| `QUAL-PMD-19` | `ProcessingPolicyFingerprint.addValue`: `debt` | Bootstrap/ARCH; зафиксировать byte oracle до refactor/moves: record FQCN, component names/order, enum names, normalized sets и framed nulls входят в durable identity |
| `QUAL-PMD-20` | `RowSources.map` current-row reset: `accepted` | Application/cursor; reset до advance/map запрещает выдачу прошлой строки после EOF/exception; local UnusedAssignment не доказывает лишнюю запись; revisit при смене cursor contract |
| `QUAL-PMD-21` | Два compatibility constructors `DataframeImportCatalogDraft.Contract`: `accepted` | Import/RETIRE; явные delegating defaults сохраняют старые call shapes; arity не обосновывает parameter bag/removal; revisit после consumer/support-window проверки |
| `QUAL-PMD-22` | Compatibility constructor `DocumentAdmission`: `accepted` | Ingestion/RETIRE; явный durable aggregate и default execution state; изменение phase/registration/execution invariants требует переходных тестов и сверки journal consumers |

Это semantic dispositions, не новые suppressions или изменения analyzer counts.
Текущие `accepted` не закрывают полный QUAL/TEST модулей; `debt` остаётся
выбираемым срезом, а не механическим требованием исправить всё до релиза.

## Критерии закрытия и граница

### Уточнённые срезы технического долга — 2026-11-17

Основание выбора — общий invariant/причина изменения или неясная ownership
граница, а не уменьшение CPD/PMD count. Ниже proposals для отдельных changes;
Решением пользователя 2026-11-18 весь предложенный пакет реализации перенесён
за 0.3.0, включая QUAL-BOOT-1. Таблица ниже сохраняет future slice boundaries,
а не текущую обязательную очередь. Постоянные записи — ARCH-6/CODE-6.
OPS-10/12/15 remediation перенесена пользователем за 0.3.0; это не закрывает
дефекты и не разрешает незаметно менять их поведение внутри refactor.

| Scope / intake | Реальный долг и минимальная boundary | Проверяемый результат / ограничение |
|---|---|---|
| QUAL-BOOT-1, PMD-13/19 | Mixed composition package; соседние configuration classes вызывают assembly helpers `AppConfig`. Сначала cohesive artifact preparation/schema/identity assembly, затем capability clusters | Зачесть существующие catalogs/factories; убрать direct configuration-object calls без public-всего; сохранить Spring graph/conditions/bean names и fingerprint bytes. Health рядом с capability; model FQCN пока прежние |
| CPD-14 | JDBC повторяет schema-name index и ordered public-header knowledge в read/write/export/receipt owners | Package-private schema helper/catalog и reuse существующего `JdbcSql` для quoting. Проверить duplicate/null/schema/header ordering и error identity; разный sort/diagnostics не нормализовать без решения. SQL, transactions, migration catalogs и OPS-15 не менять |
| CPD-16 | Lifecycle services повторяют unique/nonblank artifact-catalog invariant; третий `LifecycleReconciliationService` имеет похожую, но иную validation/error форму | Общая package-private validation только после сравнения трёх contracts; сохранить input order, copy и null/blank/duplicate outcomes. Cadence, work loops и clock authority остаются у отдельных services |
| CPD-08 | Четыре lifecycle/observation schedulers повторяют shutdown/await/interrupt protocol, но владеют разными admission/timer/follow-up states | Локальный executor-stop helper после stop-versus-follow-up, timeout и interruption characterization. Состояния и cancellation остаются в schedulers; не общий base scheduler и не расширение published concurrency API без generic requirement |
| CPD-02/03 | Общие diagnostic-summary log fields и event metadata MDC размножены у producers | Summary-only mapping в IOC observability facade с явной diagnostics API dependency; `ExtractionResult` остаётся снаружи. Общая event metadata часть — bootstrap-local; handler-specific run/profile/source fields и scope close/restore сохраняются. Published logging library не получает IOC schema |
| CPD-12 | В одном CSV reader повторяется parser/Reader stack открытия header/full read | Низкий приоритет: local owned-parser scope/callback только с доказанным TWR cleanup при частичной инициализации. Сохранить charset/separator/limit ordering, header-only/full read semantics и разные exception messages; consumer failures не переклассифицировать |
| PMD-03/04/06/14 | Длинные списки collaborators смешивают explicit ports и settings; число аргументов само по себе не доказывает плохую границу | Для import runtime оправдан кандидат immutable settings (batch sizes/intervals/timeout) после проверки его validation. Dependencies bundle/универсальный builder ради arity не вводить; Export/Ingestion/extraction ports остаются явными до доказанной cohesive boundary |
| PMD-01/02/05/07/10/11/15/16/17 | В durable orchestration много branches из-за recovery/transaction/failure precedence | Каждый метод — отдельный behavior table и characterization, затем private typed steps. Не начинать с деления saga/transaction на services, не менять атомарность/retry/primary failure. Пока это review debt, не обещание переписать все workflows до релиза |

После отдельного принятия будущей итерации приоритет по architectural effect:
разобрать bootstrap assembly/collaborators и
fingerprint oracle; выполнить изолированные catalog-validation changes; затем
выбирать executor/observability helpers и package moves. Декомпозиция durable
workflows и cosmetic arity/CPD cleanup не являются первым срезом.

CPD-05/09/10/13/15, PMD-08/09/12/13/18/20/21/22 и ARCH-LOG-1 сохраняют
явные acceptances/trigger conditions. Отличающиеся внешняя binding shape и
framework-free draft, SQL history transitions, enums и compatibility constructors
не объединяются только ради меньшего отчёта.

### Закрытие scope

Весь согласованный inventory рассмотрен; каждый finding имеет disposition;
`fix-now` выполнены либо явно изменён scope. Critical duplicated knowledge
имеет authoritative owner или rationale, changes подтверждены meaningful tests,
а docs/matrix обновлены. Отложенный 2026-11-18 structural refactoring не входит
в обязательные изменения 0.3.0; review/disposition и applicable gates остаются.
Для duplicate review сопоставляются occurrences,
knowledge/semantics, lifecycle, reason to change, divergence risk и минимальная
boundary; visibility/API/dependency impact проверяется до extraction.

CPD/PMD/coverage/dependency-analysis создают кандидатов и не разрешают mechanical
refactoring, removal или shared abstraction. Граница выбирается от private/
package-local до существующего inward module; новый shared module требует
`R030-LIB` admission. Multi-daemon lease/fencing отложен: поддерживаемый deployment
остаётся single-daemon, process-local guard не является cross-process protocol.
Возврат к этому scope требует реального deployment requirement и durable
ownership decision. Требуется `R030-BASE`; findings могут порождать отдельные
RETIRE/ARCH/TEST/LIB work items.
