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
открытыми. Историческое analyzer triage не закрывает полный review модулей.

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

`QUAL-BOOT-1` остаётся `fix-now` через `R030-ARCH`: определить capability map
плоского bootstrap package и обосновать cohesion/visibility изменений.
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

## Критерии закрытия и граница

Весь согласованный inventory рассмотрен; каждый finding имеет disposition;
`fix-now` выполнены либо явно изменён scope. Critical duplicated knowledge
имеет authoritative owner или rationale, changes подтверждены meaningful tests,
а docs/matrix обновлены. Для duplicate review сопоставляются occurrences,
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
