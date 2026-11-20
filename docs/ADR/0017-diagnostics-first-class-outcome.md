> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0017-diagnostics-first-class-outcome.md) (relative link addresses rebased on relocation).

<a id="0017--диагностика-как-first-class-результат-обработки-блок-obs-d1"></a>
# 0017 — Diagnostics as a first-class processing outcome (OBS-D1)

<a id="статус"></a>
## Status

**Accepted 2026-07-12, implementation in progress.** Slices 1–3 implemented
(`354c5c6..3d2d1d0`), 4–10 planned. Closes **OBS-D1** in
[KNOWN-ISSUES](../KNOWN-ISSUES.md), establishes **OBS-2** (`SINK.CHARSET_UNMAPPABLE`)
contract. Supersedes the “Open debt” section of
[dev/observability.md](../dev/observability.md) and integration plan in
[0008](0008-stage-6-8-implementation-followups.md). The mechanism introduced by
[0003](0003-diagnostics.md) gets an **application boundary**: what must be Diagnostic,
what remains exception, who decides continuation, how completion quality reaches
caller, and how diagnostics reach [0007 observability](0007-logging-observability.md).

Preparatory OBS contract work (OBS-1, OBS-3, CODE-4: generated LOGGING-CATALOG,
pinned severity→level, reference ratchet) was implemented separately
(`752d186..aca604e`) without an ADR, applying existing rules. This ADR records **decisions**.

The initial design discussion lived in a local scratch note, deliberately excluded
from published ADR/documentation.

<a id="контекст-и-проблема"></a>
## Context and problem

Since stage 6 (0003), platform-diagnostics provides Diagnostic/builder/factory,
code catalogs, Result/Notification, pure FailurePolicy (fail-fast/collect-and-continue),
DiagnosticSink/`DiagnosticRenderer` ports, DiagnosticException and ECS logging bridge. The mechanism
works, but **is not fully connected or exposed as use-case outcome**:

1. **collect-and-continue is dead.** IocExtractionService hardcodes
   `new PipelineRunner(FailurePolicy.failFast(), …)`; ioc.pipeline contains only
   deduplicate. No production `collectAndContinue()` use.
2. **Crashes lose diagnostics.** One sink-emission line after `runner.run(...)` in `IocExtractionService.extract(...)`;
   runner owns accumulated envelope, so stage failure loses all collected diagnostics,
   including SOURCE.MARKERS_UNMATCHED.
3. **24 of 43 catalog codes lack production references**, pinned by
   CatalogReferenceRatchetTest (`2681c4d`). EXTRACTION/CLASSIFY/PIPELINE/SINK have no
   producers; published error map promises absent runtime codes. About 30
   IocExtractorException throw sites in sink-csv/ingest/store-jdbc/source-tika adapters.
4. **Two de facto error models.** Export/sync (0011–0013) emits typed diagnostics,
   ~12 producers (ArtifactPublishService, `ExportRunRecoveryService`, migrators, ledgers), but CAS saga/
   RetryPolicy controls continuation, not FailurePolicy. Extraction emits almost none.
5. **First-class data stops at use-case boundary.** ExtractionResult assembly drops
   final envelope diagnostics; driving adapters see counts only, cannot distinguish
   clean completion from skipped-data completion.
6. **Write stage mixes preparation/commit.** JdbcIocSink filters, assigns IDs, maps,
   writes canonical storage. Returning Result<Integer> afterward would evaluate
   fail-fast **after durable side effects**, not a real checkpoint.

One bad element costs the entire file (daemon dead-letter, oneshot failure), logs have
stacktrace without machine-readable code, no skipped-element report. Meanwhile [0016]
already moved operator errors to boot; runtime duplicates no longer make sense.

<a id="решение-1--доктрина-два-контура-три-канала-и-impact"></a>
## Decision 1 — doctrine: two flows, three channels and impact

**Legitimize, rather than unify**, the flows:

| Flow | Continuation authority | Diagnostic role |
|---|---|---|
| **Envelope**: oneshot/daemon extraction | FailurePolicy over `Envelope.diagnostics()` at PipelineRunner stage boundaries | **Result data**, participates in policy/final outcome |
| **Saga**: export/publish/fetch (0011–0013) | Ledger CAS state machine + RetryPolicy | **Operation observability**, emitted beside durable saga transitions |

Three failure channels inside envelope flow:

1. **Element-level**: safely discard one row/unsupported indicator/unmappable value
   without invalidating retained result → **Diagnostic data + local stage continuation,
   even ERROR**. Producer neither throws nor decides run fate. Next boundary may stop:
   fail-fast means **stop after stage**, not immediate inner-loop exit.
2. **Run-level**: current source/run cannot continue (unparseable source, unsupported
   whole format, DB unavailable, atomic move failure) → exception remains control flow,
   now **typed observable signal**:
   - precise-code boundary throws `DiagnosticException(diagnostic)`,
     e.g. Tika `SOURCE.READ_FAILED`/`SOURCE.UNSUPPORTED_FORMAT`;
   - uncoded exception becomes PIPELINE.STAGE_FAILED(stage,cause) in runner.
   Daemon retry→dead-letter+sidecar remains unchanged.
3. **Operator configuration** → [0016] boot preflight/`CONFIG.*`, never runtime; remove duplicates.

Criterion: **smallest safely discardable unit**, not merely runtime-data dependency.
Another file with same config may work, yet unsupported current format is still run-level:
no text means no meaningful downstream stages.

Catalog metadata makes this executable: DiagnosticCode gains immutable `impact()`:

```java
public enum DiagnosticImpact {
    ELEMENT,    // влияет на один элемент envelope-потока
    RUN,        // относится ко всему текущему run/source
    OPERATION   // относится к операции со своей policy/state machine
}
```

Impact is neither inferred from severity/category nor producer-overridable. RUN alone
is not stopping: `SOURCE.EMPTY_TEXT` RUN/WARN versus `SOURCE.READ_FAILED` RUN/ERROR + exception.
Channel+severity+policy control flow; impact identifies the processing unit described.
Publish in DIAGNOSTICS-CATALOG, support report/quarantine filtering, distinguish element
ERROR from operation ERROR. Boot codes have different lifecycle, outside this enum flow.

<a id="решение-2--эмиссия-по-границе-стадии-без-дублей"></a>
## Decision 2 — stage-boundary emission without duplicates

PipelineRunner has four explicit dependencies: FailurePolicy, PipelineObserver,
DiagnosticSink, DiagnosticFactory (generic STAGE_FAILED). ETL already depends on
platform-diagnostics, no dependency-direction change.

Canonical stage protocol:

1. Open observer/MDC scope, remember incoming diagnostic count.
2. Execute stage.process(stageInput).
3. If envelope returned, emit only new diagnostic tail.
4. **After flush**, evaluate cumulative FailurePolicy.
5. On success only, stageCompleted and close scope.

Separate exception and policy paths:

- Stage/port DiagnosticException emits contained diagnostic once inside scope,
  marks failed, rethrows.
- Other stage exception becomes STAGE_FAILED, emitted once; observer failure;
  outward DiagnosticException holds generated diagnostic and original cause.
- Policy DiagnosticException **after flush** is not re-emitted: already in delta.
  Observer records rejection, then rethrow.

Earlier diagnostics survive, with no within-run duplicate accounting. Daemon retry
is a new run and legitimately new occurrences; cross-run dedup belongs to future
durable report/quarantine ledger. Remove final IocExtractionService flush. Synchronous
sink still runs inside stage scope with run/stage MDC; pin MDC in tests.

DiagnosticException carries a **stopping diagnostic**, not necessarily FATAL:
fail-fast may carry ERROR. Update Javadoc.

<a id="контракт-отказа-diagnosticsink"></a>
### DiagnosticSink failure contract

Ordinary sink is an observational driven port and **cannot change processing outcome**.
Production wraps delegates in nonthrowing **ResilientDiagnosticSink**: isolate consumer
failure, signal low-level health/operations separately without recursing into same
port. Outer bridge/bootstrap composes decorator; diagnostics gets no SLF4J/health
coupling. Future `CompositeDiagnosticSink` isolates delegates.

Correctness-bearing delivery (mandatory durable quarantine) needs separate port/ledger
and explicit orchestration policy, not a best-effort sink. Never silently depend on
logger/report-writer availability.

Migrating typed producers removes local duplicate error log. Lifecycle `stage_complete/failure` event and
diagnostic may coexist; third Tika/mapper stacktrace would duplicate the fact.

<a id="решение-3--configurable-continuation-и-явный-completion-outcome"></a>
## Decision 3 — configurable continuation and explicit completion

New typed selector `ioc.pipeline.failure-policy: fail-fast | collect-and-continue`,
following [0016]; strict preflight rejects unknown key/value without extra codes.
Default **fail-fast**, avoiding silent install behavior change. Production daemon
`packaging/templates/application.yml` explicitly **collect-and-continue**: bad element must not cost feed file.

FailurePolicy:

- fail-fast stops at first ERROR/FATAL seen **at stage boundary**;
- collect continues ERROR, stops FATAL;
- max-errors/per-code/per-impact thresholds extend same Strategy, not producers.

Continuation and completion quality differ. Final use-case result gets:

```java
public enum CompletionStatus {
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    COMPLETED_WITH_ERRORS
}
```

ExtractionResult returns immutable budgeted diagnostics (Decision 7), aggregate counts,
CompletionStatus plus extracted/retained/written. FATAL throws, produces no result.

Driving boundaries:

- Oneshot CLI summary; COMPLETED_WITH_ERRORS exit **3**, distinct from picocli
  SOFTWARE=1/USAGE=2, even if policy allowed commit.
- Daemon archives after successful canonical commit; IngestSourceResult retains
  degraded ExtractionResult.
- Saga COMPLETED means write→projection protocol completion, not data quality.
  Durable diagnostic summary in ingest_run only with report/quarantine consumer,
  avoiding premature mixing of state machine and analytics.

<a id="решение-4--две-дороги-и-policy-checkpoint-до-commit"></a>
## Decision 4 — two routes and pre-commit policy checkpoint

Diagnostics travel **through data** (port→stage→envelope→policy), or **directly to sink**,
bypassing policy. Rule:

> **Envelope ELEMENT/RUN ERROR/FATAL only through data. Advisory WARN or below may
> go direct. Saga OPERATION diagnostics go direct regardless of severity, since
> ledger/CAS/retry decides.**

An advisory affecting completion/report must also travel through data regardless of
severity. Direct-only advisory is valid for an external operation outside extraction
outcome (e.g. post-commit CSV projection), or as an additional observation of an
already-accounted fact, never the sole route for a pipeline warning.

Changing `IocSink.write()` to Result<Integer> is insufficient: durable write already done.
Split mixed sink by responsibility:

```text
RetainedIndicators
        │
        ▼
PrepareArtifactsStage
  ArtifactPreparer.prepare(...) -> Result<ArtifactWritePlan>
  - filter / classify once / map per row
  - no DB/filesystem I/O
  - no advancement of persistent/shared ID sequence
        │  diagnostics appended to Envelope
        ▼
PipelineRunner flush + FailurePolicy checkpoint
        │  only accepted envelope continues
        ▼
WriteArtifactsStage
  - materialize/advance IDs
  - CanonicalArtifactRepository.write(...)
  - optional oneshot projection after successful commit
        │
        ▼
ArtifactWriteSummary
```

ArtifactPreparer is application out-port for config-driven preparation; mapper-based
implementation remains `adapter-sink-csv`. Immutable ArtifactWritePlan holds identity/header,
valid row candidates, commit data, **no external resource/captured transaction**.
Materialize public IDs **after policy checkpoint**; rejected run consumes no shared
sequence, retry starts at same baseline.

Move pure IdGenerator/strategy into `ioc-application/artifact` with identity. Candidate marks
commit-time ID slot, application sequence fills only in WriteArtifactsStage. DB remains
unaware of CSV DSL/ID direction. Advancement+write form one application commit step,
not called on mapping rejection; pin storage-exception sequence behavior separately,
never disguise it as element failure.

Sequence reserves ranges atomically, **never reuses**. Storage failure after reservation
may leave safe gaps; issuing same ID to another run forbidden. Cheaper/safer than
distributed compensation; pre-commit mapping/policy rejection still consumes nothing.

PrepareArtifactsStage collects all configured artifacts, appends one diagnostic batch,
no side effects. Write uses existing CanonicalArtifactRepository; oneshot gets CSV
projection, daemon noop because IngestionService owns durable write→projection saga.

Mapping failure must be **expected typed data outcome**, not broad `catch (RuntimeException)`. ROW_MAPPING_FAILED only for classified data defects. Unknown providers/transforms
rejected at boot; `NullPointerException`/header invariant/programming defects stay RUN STAGE_FAILED.
Collect must not hide bugs as bad rows.

Canonical batch failure stays RUN `SINK.WRITE_FAILED` + exception, atomic per artifact.
No SQL row savepoint/retry; preparation filters element defects. Multi-artifact commit
is not one transaction, as before; daemon ledger/retry and keep-first recover.

OBS-2 CHARSET_UNMAPPABLE is advisory WARN: immutable export keeps fail-fast encoding;
mutable projection emits direct, since replacement does not change canonical continuation.

<a id="решение-5--миграция-продьюсеров-и-единоличное-владение-фактом"></a>
## Decision 5 — producer migration and sole fact ownership

| Category | Disposition | Producer / channel |
|---|---|---|
| CONFIG (2: INVALID_PROPERTY, UNKNOWN_POLICY) | **Remove**, superseded by boot [0016] | — |
| SOURCE (3) | READ_FAILED/UNSUPPORTED_FORMAT RUN exceptions; EMPTY_TEXT RUN/WARN envelope, empty downstream payload | TikaSourceReader / ReadSourceStage |
| EXTRACTION (3) | INDICATOR_SKIPPED ELEMENT DEBUG; AMBIGUOUS_VALUE ELEMENT WARN; remove PATTERN_INVALID if preflight covers all startup compilation | ExtractIndicatorsStage |
| CLASSIFY (2) | ELEMENT; UNSUPPORTED_INDICATOR_TYPE keeps ERROR, drops only indicator | one classification outcome before mapping |
| PIPELINE | STAGE_FAILED RUN; ITEM_SKIPPED ELEMENT DEBUG dedup/filter; add Decision 7 suppression summary | runner, application stages |
| SINK | ROW_MAPPING_FAILED ELEMENT/Result preparation; WRITE_FAILED RUN/exception commit | preparer / WriteArtifactsStage |
| INGEST (3) | OPERATION/RUN: emit + existing throw; file ledger/retry/dead-letter controls continuation | adapter/application boundary |
| SYNC | OPERATION: map RemoteErrorKind only on final failure after Retrier; remove boot-superseded codes | `RemoteFetchService`/`ArtifactPublishService` |

One fact, one owner. Compute classification **once per indicator** into
ClassificationDecision; match.url/match.host, filters, diagnostics, TRACE share it,
never call MatchPolicy again. Prevents duplicate diagnostics and reduces CODE-3.

Saga diagnostic follows successful durable failed/abandoned transition, or explicitly
reports inability to persist it. Events never replace ledger: only post-commit
claim-check hints per [0013].

Ratchet allowlist burns down to zero, decreasing each slice. Regenerate catalog
(`-Dioc.docs.update=true`) for each change, including Impact column.

<a id="решение-6--чистые-domain-outcomes-и-per-item-trace"></a>
## Decision 6 — pure domain outcomes and per-item TRACE

Current String/List<RawIndicator>/MaskMatch APIs cannot explain rule/span end/features/
predicates for full TRACE. **No recomputation for logging**. Domain returns **pure
decision data**, no logging/SLF4J/observability dependency:

- `RefangOutcome(text, changes)`, each change rule identity + before/after span/value;
- extraction matches include pattern/type/full span; overlap/drop decisions in data,
  no repeated application search;
- ClassificationDecision(features,matchedRule,maskMatch,predicateResults), reused by providers/filter;
- attribution chosen marker/source or absence reason;
- application stages already own dedup/routing and create compact decision records.

Outcomes are functional results, not log DTOs; eliminate recomputation, useful in tests/
diagnostics without TRACE. No duplicate heavyweight payloads. After measurement,
optional detail mode if allocation material; **no second `withTrace()` API before evidence**.

Application emits decisions only behind double gate:

```text
logger TRACE enabled
AND ioc.observability.per-item-trace-enabled = true
```

One stable pipeline_item_decision action, minimal fields for kind/type/identity/rule/
pattern/outcome. IOC values only explicit-flag TRACE, query/token redacted; default
short hash+type/span. Tests pin **zero rendering/serialization/emission** when either
gate closed. TRACE is operational, **not Diagnostic**, outside policy/ratchet/completion.

<a id="решение-7--контекст-bounded-accumulation-и-delivery-identity"></a>
## Decision 7 — context, bounded accumulation and delivery identity

Element diagnostics identify type/artifact/source/safe item. Full identity does not
mean raw IOC at WARN/ERROR. Separate:

- **Policy/report context**: exact structured Diagnostic data for trusted in-process consumer;
- **Rendered/log context**: redacted view. Raw IOC DEBUG/TRACE-only per
  [observability](../dev/observability.md), URL queries/tokens masked or short-hashed.

Per-diagnostic emission, bounded retention. Positive
`ioc.pipeline.max-diagnostics-per-run`, default **10 000**. Stage-local BoundedNotification:

1. Keep first N occurrences, counts per code/severity/impact.
2. Never lose first policy-rejecting ERROR/FATAL.
3. Overflow adds one synthetic PIPELINE.DIAGNOSTICS_SUPPRESSED with total/counts/maxSeverity.
4. Effective max severity reaches policy; cap never turns fail-fast into continue.
5. Batch append to envelope; no per-item `withDiagnostic()` quadratic list copying.

Summary counts retained **and suppressed**; TRACE has independent volume gate/budget.

MDC suffices synchronous sink, not durable/async delivery. When report/quarantine activates,
introduce DiagnosticOccurrence (diagnostic + run/source/stage + monotonic sequence/stable ID).
Explicit sink-boundary evolution; never derive durable identity from thread-local MDC
or unstable Map.hashCode.

<a id="решение-8--карантин-и-дополнительные-consumers-отложено"></a>
## Decision 8 — quarantine and additional consumers (deferred)

Impact, safe identity, per-diagnostic emission and future occurrence support **Invalid
Message Channel**. Daemon failed/ remains whole-source Dead Letter Channel.

Future durable composition:

```text
ELEMENT ERROR occurrence
        -> QuarantineStore (idempotent durable write; truth)
        -> optional QuarantinedItemsChanged claim-check event
        -> report/UI/reprocess consumer
```

Optional quarantine can delegate resilient composite sink. If operator requires no
silent loss, QuarantineStore write participates in orchestration; failure is RUN.
Best-effort sink cannot guarantee it.

Activate for real manual-triage need; no ledger/schema/event until then. Register seam
in KNOWN-ISSUES when D1 closes. Also deferred:

- ReportDiagnosticSink/JSONL, durable run summary;
- low-cardinality code/impact/severity metrics;
- max-errors/per-code/per-impact thresholds;
- i18n renderer;
- OpenTelemetry bridge. Diagnostics are not control events, no broker/event sourcing/CQRS.

<a id="отклонённые-альтернативы"></a>
## Rejected alternatives

- **`PipelineObserver.stageDiagnostics(...)` bridge**: second route to same port, mixes
  lifecycle/result, risks duplicates. Real metrics/reports attach to diagnostic delivery.
- **Single-phase `IocSink.write() -> Result<Integer>`**: after durable write, fail-fast cannot prevent
  effects; daemon might report failed after partial commit. Separate preparation.
- **Railway-Oriented List<Ok|Failed> payloads**: cleaner theoretically, rewrites every
  payload; append-only envelope + outcomes gives semantics with less change.
- **SQL row savepoint recovery**: disproportionate coupling/cost, filter before atomic batch.
- **Broad RuntimeException mapping catch**: hides programming/config bugs under collect;
  only typed element outcome allowed.
- **Numeric ECS event.severity**: typed fields belong to OBS-D3.
- **Broker/durable diagnostic queue**: sink/ledger seams sufficient, preserve 0013.
- **Softened FATAL**: run untrustworthy; collect still stops.

<a id="план-реализации-срезы"></a>
## Implementation plan (slices)

One independent commit per slice, dependency order; only previously published contracts.
No temporary cross-module APIs knowingly removed next commit.

<a id="1-catalog-impact-как-исполнимый-контракт"></a>
### 1. Catalog impact as executable contract

**Commit:** `FEATURE: OBS-D1 classify diagnostic impact`

- Add DiagnosticImpact and `DiagnosticCode.impact()`; classify remaining constants
  ELEMENT/RUN/OPERATION.
- Extend `DiagnosticCatalogEntry`, generator and document with Impact.
- Structural tests nonnull impact/generated sync.
- Do not remove superseded codes until producer migration; additive first commit.

**Modules:** diagnostics, docs. **Gate:**
`./mvnw -pl platform/platform-diagnostics -am test` + doc-update test.

<a id="2-exactly-once-delivery-protocol-в-runner"></a>
### 2. Exactly-once runner delivery protocol

**Commit:** `FEATURE: OBS-D1 emit pipeline diagnostics exactly once`

- Add sink/factory, separate stage exception, delta flush, policy rejection.
- Generic RUN stage-failed, typed unwrap without generic duplicate.
- No policy re-emission; remove extraction final flush.
- Nonthrowing resilient outer bridge, compose bootstrap, no inward SLF4J.
- Update DiagnosticException stopping contract.

Keep producer logs until migration: still their only precise signal.

**Modules:** ETL, diagnostics, diagnostics-logging, application, bootstrap.
**Gate:** success/typed/generic/policy runner paths, MDC pin, throwing delegate isolation;
`./mvnw -pl platform/platform-etl,platform/platform-diagnostics-logging,core/ioc-application -am test`.

<a id="3-configurable-policy-budget-и-completion-outcome"></a>
### 3. Configurable policy, budget and completion

**Commit:** `FEATURE: OBS-D1 expose diagnostic completion outcome`

- Typed PipelineFailurePolicy, failure-policy/max-diagnostics-per-run (10 000) across
  model/defaults/schema tests/packaging.
- BoundedNotification/summary, first rejecting diagnostic, exact suppression/synthetic marker.
- CompletionStatus + immutable diagnostics/summary in ExtractionResult through IngestSourceResult.
- Root wiring fail-fast default, production collect.
- Pin CLI error completion exit 3; structurally completed daemon with degraded result.

**Modules:** diagnostics, `ioc-application`, `adapter-cli-picocli`, bootstrap, packaging.
**Gate:** config/preflight, both policies, cap preserves rejection, CLI/daemon outcomes;
targeted reactor tests through bootstrap.

<a id="4-pure-outcomes-refangextractionattribution"></a>
### 4. Pure refang/extraction/attribution outcomes

**Commit:** `REFACTOR: expose domain processing decisions`

- Replace scalar/list-only replies with compact immutable functional payload + rule/
  pattern/span/marker/drop facts.
- Atomically migrate stages/regex consumers.
- No domain logging/diagnostics; no TRACE yet, API stands independently.
- Preserve golden extraction output.

**Modules:** `ioc-domain`, `ioc-application`, `adapter-regex-re2j`, bootstrap.
**Gate:** complete decision facts, stages, golden pipeline, ArchUnit.

<a id="5-единственный-classification-outcome-на-indicator"></a>
### 5. One classification outcome per indicator

**Commit:** `REFACTOR: materialize classification decisions once`

- ClassificationDecision features/matched rule/predicates/MaskMatch;
  RuleBasedMatchPolicy returns without repeated extraction.
- Materialize once after attribution/before preparation.
- match.url/host, address providers/filters share decision instead of repeated
  MatchPolicy/FeatureExtractor.
- Keep declarative first-match-wins and all four variants.
- Counting tests independent of column/provider count.

**Modules:** `ioc-domain`, `ioc-application`, `adapter-sink-csv`, bootstrap.
**Gate:** classify/domain, mapping/golden; counting performance regression, no timing assertion.

<a id="6-artifact-preparation--policy-checkpoint--commit"></a>
### 6. Artifact preparation → policy checkpoint → commit

**Commit:** `FEATURE: OBS-D1 reject invalid rows before canonical commit`

- Replace mixed sink with preparer/immutable plan + repository commit.
- Add PrepareArtifactsStage before WriteArtifactsStage.
- Move pure ID strategy/sequence to application/artifact, fill slot post-checkpoint.
- Typed ELEMENT mapping failure only, no broad runtime catch.
- Storage/batch RUN write-failed.
- Keep oneshot post-commit projection and daemon saga.

**Modules:** `ioc-application`, `adapter-sink-csv`, `adapter-store-jdbc`, bootstrap.
**Gate:** 4999/1: fail-fast 0 writes/ID advancement; collect writes 4999 with errors;
SQL failure RUN; reserved failed-storage range never reused; repository/TCK/recovery.

<a id="7-envelope-producer-migration"></a>
### 7. Envelope producer migration

**Commit:** `FEATURE: OBS-D1 produce typed pipeline diagnostics`

- SOURCE/EXTRACTION/CLASSIFY/PIPELINE from prior outcomes, stage-local batches.
- Typed read/format exceptions; empty text envelope WARN.
- ELEMENT classification/data drops, safe identity.
- Remove duplicate Tika/mapper logs after runner delivery.
- WARN/ERROR redaction; reduce allowlist only for connected constants.

**Modules:** `ioc-application`, `adapter-source-tika`, `adapter-sink-csv`, diagnostics-logging, bootstrap.
**Gate:** producer contracts, no raw IOC WARN/ERROR, exact code/cause/MDC on throw,
both policies with mixed batch.

<a id="8-structured-per-item-trace"></a>
### 8. Structured per-item TRACE

**Commit:** `FEATURE: OBS-D1 trace per-item pipeline decisions`

- Action/minimal LogField constants, regenerate logging catalog.
- Actual per-item-trace-enabled consumer flag.
- Refang/extract/classify/attribute/dedup/route from existing outcomes, no recomputation.
- Double gate before render/serialize; TRACE raw/query policy.
- Allocation/runtime smoke golden + synthetic large input, no flaky unit timing gate.

**Modules:** `platform-observability`, application, bootstrap, generated docs.
**Gate:** taxonomy/sync, both gate-off zero emissions/render calls, gate-on capture,
classification counting.

<a id="9-ingestsync-producers-и-финальная-чистка-catalog"></a>
### 9. Ingest/sync producers and final catalog cleanup

**Commit:** `FEATURE: OBS-D1 complete diagnostic producer coverage`

- INGEST at ledger/retry/dead-letter boundaries.
- Neutral RemoteErrorKind→SYNC at final failure after retry + durable transition;
  listing/detection same rule.
- Remove ConfigDiagnosticCodes/PATTERN_INVALID/superseded SYNC only after live producer
  audit confirms boot/state-machine replacement.
- Allowlist ∅, regenerated catalog matches producers.
- Pin diagnostics never replace ledger; control events no diagnostic payload.

**Modules:** `ioc-application`, `adapter-ingest`, `adapter-transport-smb`,
`adapter-store-jdbc`, bootstrap, diagnostics, generated docs.
**Gate:** final-failure mapping, recovery/dead-letter/sync saga, empty ratchet, architecture.

<a id="10-published-docs-registry-и-итоговый-review"></a>
### 10. Published docs, registry and final review

**Commit:** `DOCS: close OBS-D1 and publish diagnostic outcome contract`

- Update dev/DIAGNOSTICS.md, dev/pipeline.md, dev/LOGGING.md, READMEs, config/packaging,
  known issues.
- Close D1 with commits; register deferred quarantine/report/occurrence separately.
- Generated catalogs via update mode, no manual edits.
- Holistic dependency/OOP/SOLID/exception/sink-isolation/concurrency/allocation review.
- Final `./mvnw verify`, links, `git diff --check`.

**Modules:** docs/READMEs, no production changes except review blocker in separate fix,
never hidden in DOCS. Allowlist only decreases. Targeted tests each commit; full verify
after 3/6/9/10: external checkpoints policy/outcome, durable write, producer coverage, delivery.

<a id="обязательные-инварианты-и-тесты"></a>
## Required invariants and tests

- Each occurrence emits at most once per run.
- Stage exception emits before MDC closes.
- Policy never re-emits flushed facts.
- Observational sink failure changes no outcome, no recursion.
- Fail-fast mapping ERROR writes/reserves nothing.
- Collect saves valid rows only, returns degraded completion.
- RUN failure never disguised as element.
- Cap never hides ERROR/FATAL, exact suppression summary.
- No raw IOC WARN/ERROR.
- TRACE no render/emission with either gate closed.
- Classification/features once per indicator.
- Saga diagnostic neither ledger transition nor control event.

<a id="следствия"></a>
## Consequences

- Default fail-fast remains, now genuinely checked **before canonical commit** of
  element-defective batch.
- Production collect saves valid elements, exposes skipped data as errors rather
  than clean success.
- No diagnostic loss on stage failure, no producer/runner/policy duplicates.
- Diagnostics gains impact/bounded semantics, ETL runner protocol, domain pure
  outcomes, application completion/two stages/ports, adapters preparer/producers,
  bootstrap typed config/wiring.
- Dependency direction retained: pure domain; application inward contracts;
  mapping/storage/logging outer; Spring policy/config only composition root.
- LoggingDiagnosticSink keeps OBS-1 severity→level; surrounding redaction/resilience,
  no policy in bridge.
- OBS-D3 typed ECS remains outside scope.

<a id="ссылки"></a>
## References

[0003](0003-diagnostics.md) — mechanism ·
[0007](0007-logging-observability.md) — ECS/MDC ·
[0008](0008-stage-6-8-implementation-followups.md) — original integration plan ·
[0013](0013-event-driven-coordination.md) — anti-broker, claim-check, sagas ·
[0016](0016-config-preflight-strict-binding.md) — operator boot errors ·
[observability](../dev/observability.md) ·
[LOGGING-CATALOG](../LOGGING-CATALOG.md), [DIAGNOSTICS-CATALOG](../DIAGNOSTICS-CATALOG.md) ·
OBS step 1: `752d186..aca604e` (ratchet baseline `2681c4d`).

<a id="дополнение-2026-07-13--итог-реализации"></a>
## Addendum 2026-07-13 — implementation outcome

Replaces only the initial implementation status: slices 1–9 and final review complete.
Decisions 1–8 unchanged; precise implementation below.

Implementation chain:

| Slice | Commit(s) |
|---|---|
| 1 | `354c5c6` |
| 2 | `28b2f53` |
| 3 | `49ecfd9`, `3d2d1d0`; follow-up bounded/UX fix `53077c9` |
| 4 | `ad7b08b` |
| 5 | `4038173`; ordering/performance fix `5b44f11` |
| 6 | `2c18bbd` |
| 7 | `0dee6cd` |
| 8 | `ae7f268` |
| 9 | `32b4202` |
| Final review | terminal delivery `d34b733`, cleanup `c990b98` |
| Stand deviations 1–4 | `d39da37`, `e8c4d3b`, `0ffba22`, `557de88` |

Live-code clarifications:

1. RefangDecision retains applied rule/count, not every replacement's before/after
   metadata: full rule-level TRACE, allocation bounded by rules rather than replacements.
   Extraction still exact pattern/span/status.
2. Batch dedup moved before classification: pure type|value decision, unchanged
   retained outcome, no duplicate feature cost. NETWORK only, neutral FILE decision.
3. Remove CLASSIFY.AMBIGUOUS_MATCH: intentional first-match-wins, overlapping rules not
   domain defect. Emitting without new invariant would be false warning.
4. Add INGEST.DEAD_LETTER_FAILED to three planned constants: physical failed-area
   transition is separate operation, not LEDGER_WRITE_FAILED.
5. Sink-csv owns routing decision: knows configured filter/typed mapping. Sends compact
   application-owned PipelineItemDecision through port; no logging import in application/mapper.
6. Final review pins durable FAILED retry cannot become false success/lose original
   diagnostic; suppression summary before policy rejection as well as normal completion.

Still deferred: durable occurrence, JSONL/report, element quarantine/reprocess,
thresholds, i18n, metrics/OTel. No broker needed; ledger-first/anti-broker unchanged.

<a id="дополнение-2026-07-14--верификация-на-стенде-отклонения-реализации"></a>
## Addendum 2026-07-14 — stand verification: implementation deviations

RC 0.1.1 stand tests confirmed core contract (exactly-once, budget/suppression counts,
both TRACE gates, dedup→classify once, typed terminal INGEST/SYNC, degradation/recovery)
and found **four implementation deviations**, to close before 0.1.1 tag. Decisions
1–8 are not reconsidered.

1. **Incomplete DEBUG/TRACE redaction (Decision 7 violation).** Context formatter
   returns raw values without query/token masking; DEBUG `EXTRACTION.INDICATOR_SKIPPED` exposes
   full URL query, while structured TRACE masks correctly. Decision 7 has no level
   exception. Fix query/token sanitization at every severity; raw base indicator only
   DEBUG/TRACE; align dev/LOGGING.md.
2. **Driving boundaries hide degraded completion (Decision 3 incomplete).** CLI exits
   3 but no promised summary, only row counts. Daemon `FileSourceMessageHandler` ignores `IngestSourceResult.extractionResult`,
   logs success without status/counts/run ID. CLI must show completion/suppression/
   severity counts; daemon fields and outcome must expose degradation.
3. **Suppression outside MDC and missing abort paths (2/7 gap).** Final suppressed
   diagnostic after last stage scope lacks run ID; stage throw emits no summary
   (policy rejection fixed d34b733). Run MDC around run, summary on every terminal path.
4. **Dormant `SINK.ROW_MAPPING_FAILED` producer (4 gap).** No production RowMappingException
   throw: `ConfigurableRowMapper` does not classify provider/transform data failures; element ERROR/
   COLLECT degraded completion unreachable through data. Reference ratchet legitimately
   passes (reference≠emission, as Javadoc says). Classify data-dependent failures at
   mapper; programming defects remain STAGE_FAILED.

Same stand run exposed prior daemon bugs (recovery/poller race, retry×run-ledger resume,
hashing outside bounded boundary, poison after failed claim), registered **ING-10..ING-13**
in [known issues](../KNOWN-ISSUES.md), outside 0017.

<a id="дополнение-2026-07-14--дизайн-закрытия-стендовых-отклонений"></a>
## Addendum 2026-07-14 — design for closing stand deviations

Specifies closure without changing Decisions 1–8 or expanding to ING-10..ING-13.
Reject isolated call-site patches: cross-cutting subsystem contracts need single ownership.

<a id="1-единая-политика-безопасного-представления-log-values"></a>
### 1. One safe log-value representation policy

Two independent operations:

1. **Sanitize sensitive components at every level**: no URL query/credentials even
   DEBUG/TRACE. Standalone secret/token context values require explicit sensitive keys,
   always hidden.
2. **IOC disclosure depends on severity**: DEBUG/TRACE may show sanitized IOC;
   INFO/WARN/ERROR/FATAL only `[redacted:sha256:<short-hash>]`.

Hash original, not sanitized value, retaining exact identity without disclosure.
No heuristic token-like substring detection: false positives and no security boundary.
Producers pass IOC through known DiagnosticContextKeys; new secrets require explicit
sensitive-key contract.

One stateless implementation, e.g. SensitiveLogValueSanitizer, in observability,
independent of diagnostics/SLF4J. Shared by:

- RedactingDiagnosticContextFormatter for raw keys and their occurrences in adjacent values;
- LoggingPipelineDecisionTracer replacing private `redactQuery()`.

Linear string scan, no URI parsing/normalization: malformed/partial IOCs valid;
observability must neither change meaning nor throw parsing exceptions. No new library/
regex heuristics.

Executable checks:

- Every `DiagnosticSeverity`: no query/credentials, base IOC only DEBUG/TRACE;
- INFO+ hash matches original, not sanitized;
- embedded raw value, malformed/partial URL, fragment, no-query input;
- same diagnostic/TRACE sanitizer contract;
- immutable source context unchanged.

<a id="2-run-lifecycle-как-bracketed-observation-scope"></a>
### 2. Run lifecycle as bracketed observation scope

Run ID/source ID/path/mode belong to the whole run, not stage. Framework-free
PipelineObserver lifecycle seam:

```java
default AutoCloseable openRun(EnvelopeMeta meta) {
    return () -> { };
}
```

`LoggingPipelineObserver.openRun(...)` creates stable-field MdcScope;
`openStage(...)` nests only `ioc.stage`.
Close restores prior MDC. Synchronous runner promises no implicit ThreadLocal propagation
across executor/reactive boundaries.

One structural terminal-delivery branch in `PipelineRunner.runWithOutcome(...)`:

```text
open run scope
  try
    open/close stage scopes and execute stages
  finally
    emit suppression summary once
close run scope
```

Remove special success/policy-summary calls. Finally uses last materialized `current.diagnostics()`,
not fresh `BoundedNotification.diagnostics()` materialization (would create a new synthetic timestamp).
On throw, Stage attach-XOR-throw leaves aborting stage input as last consistent snapshot;
its typed terminal diagnostic already emitted separately inside stage scope.

Summary is RUN-level: `ioc.run.id`, deliberately **no false stage** after stage scope closes.
Composition-root resilient sink owns isolation; runner does not duplicate swallow/retry.

Tests:

- normal, policy rejection, typed/generic stage throws;
- early suppression + later throw → exactly one summary;
- no suppression → no synthetic emission;
- summary after stage closes/before run closes;
- preexisting MDC restored;
- parallel synchronous runs on different threads keep IDs separate.

Micrometer propagation/task decorators deferred until real async stage boundary,
not added to current synchronous ETL.

<a id="3-единая-run-identity-и-terminal-completion-на-driving-boundaries"></a>
### 3. One run identity and terminal completion at driving boundaries

Stop hidden UUID generation in extraction service. ExtractionCommand requires runId,
no optional/default/two-argument compatibility constructor:

- CLI creates before COMMAND_START, logs/passes it;
- daemon passes durable `run.runId()` after `RunLedger.startIngest(...)`;
- EnvelopeMeta, diagnostics, result, saga, event share ID;
- ExtractionResult explicitly returns terminal-envelope ID.

Removes distinct durable-ingest/nested-pipeline UUIDs. Driving boundary owns use-case
correlation metadata; no hidden application fallback generator.

Taxonomy expands only with producers:

- EventAction.SOURCE_INGEST terminal daemon; SOURCE_READ stays source-reader-owned;
- ioc.completion.status;
- `ioc.diagnostic.total`, `ioc.diagnostic.suppressed` and exact
  `ioc.diagnostic.count.{fatal,error,warn,info,debug,trace}`.

LogField constants + generated catalog. Until OBS-D3 use existing MDC string bridge;
not an excuse for opaque map strings instead of separately queryable fields.

Terminal presentation:

| Completion | Level | event.outcome | Driving result |
|---|---|---|---|
| COMPLETED | INFO | success | exit 0 / daemon success |
| COMPLETED_WITH_WARNINGS | WARN | success | exit 0 / success with warnings |
| COMPLETED_WITH_ERRORS | WARN | failure | exit 3 / structurally complete daemon |

No nonstandard degraded ECS outcome; precise status field, ECS success|failure|unknown.
WARN exposes terminal degradation without another ERROR duplicating element diagnostics.

CLI prints run/completion/total/suppressed/nonzero severities before row counts.
Daemon uses extractionResult for same structured fields. Duplicate skip without result
gets no synthetic completion, separate successful-duplicate message.

Test stdout/exit 0/3, all daemon statuses, duplicate null safety, identical IDs across
ledger/result/MDC/terminal event.

<a id="4-typed-exception-translation-на-mapping-spi-boundary"></a>
### 4. Typed exception translation at mapping SPI boundary

Mapper **does not catch arbitrary RuntimeException**, which would turn NPE/registry/
config/programming defects into ELEMENT and permit commit after invariant failure.

Two contracts:

- MappingValueException: provider/transform explicitly reports rare expected
  input-dependent element failure;
- RowMappingException: mapper translates **only** MappingValueException, adding safe
  column/component kind/registered name.

```text
provider / transform
  ├─ MappingValueException → RowMappingException → SINK.ROW_MAPPING_FAILED
  └─ other RuntimeException → PIPELINE.STAGE_FAILED
```

ValueProvider/Transform Javadoc permits typed exception only for inability to build
cell from input, not unknown registry/config/invariant defects. Preparer diagnostic
safe metadata: artifact/column/component kind/name/type/ordinal/source/redacted identity;
raw logging follows common policy.

Built-ins currently total functions. Do not invent validation/failing production
component just to activate policy: would create fictional business rule. Prove reachability
with **real `ConfigurableRowMapper` + `CsvArtifactPreparer`** and registered typed-failing provider/transform, not fake `RowMapper`.

Integration checkpoint:

- collect saves valid rows, returns with errors;
- fail-fast reserves no IDs, calls no canonical write/projection;
- exact safe identity/mapping location diagnostic;
- unexpected IllegalStateException stays RUN STAGE_FAILED;
- happy path no extra result-wrapper allocations, just cheap exception boundary.

Real future required/one-of/format invariant may add typed RowConstraint under same
protocol; no speculative DSL now (YAGNI).

<a id="границы-реализации-и-нарезка"></a>
### Implementation boundaries and slices

No new layer dependencies:

- observability owns sanitizer/run MDC/taxonomy;
- ETL run lifecycle/exactly-once terminal delivery;
- diagnostics-logging common redaction;
- application explicit identity/completion;
- driving adapters presentation only;
- sink-csv exception translation;
- bootstrap composition.

No Spring Batch skip policy, Bean Validation, Micrometer Observation or URL parsing
library: adds coupling without solving failure classification/lifecycle ownership.

Proposed commits:

1. `FIX: enforce sensitive log value sanitization`;
2. `FIX: scope terminal diagnostics to pipeline run`;
3. `FEATURE: publish correlated extraction completion`;
4. `FIX: classify typed row mapping failures`.

Update affected docs/catalog per commit; targeted tests each, full verify/links/diff
after fourth. Never alter ING-10..ING-13 startup/retry/resume/hashing/claim/recovery.

<a id="дополнение-2026-07-15--obs-2-projection-outcome-входит-в-completion-contract"></a>
## Addendum 2026-07-15 — OBS-2: projection outcome joins completion contract

**Supersedes only** Decision 4's post-commit CSV direct-only advisory example and final
CHARSET_UNMAPPABLE paragraph. Rest of Decisions 1–8, pre-commit checkpoint, two diagnostic
routes, envelope/saga separation unchanged.

Direct WARN exposed replacement in logs while driving result stayed clean, a contradiction:
canonical committed but derived CSV degraded, CLI/daemon COMPLETED. Mutable projection
belongs to current extraction/ingest outcome, even after daemon DB checkpoint for recovery.
Advisory must be typed occurrence **and** terminal quality, never retroactive policy/rollback.

<a id="контракт-projection-boundary"></a>
### Projection boundary contract

ArtifactProjection returns immutable outcome:

```java
ProjectionOutcome project(ArtifactProjectionRequest request);

record ArtifactProjectionRequest(String runId, String artifactName) { }

record ProjectionOutcome(int projectedRows, List<Diagnostic> diagnostics) { }
```

Caller supplies runId: `EnvelopeMeta.runId` oneshot, durable `ingest_run.runId`
daemon/recovery.
Adapter invents no identity, no logging/MDC dependency. Normal return means successfully
installed derived artifact; exception hard failure. No separate completion/status boolean:
projection is not its own use case.

Only WARN-or-lower diagnostics, enforced by outcome invariant. Post-commit ERROR/FATAL
cannot be returned as data: policy would see it after irreversible effect. Hard failure
throws; oneshot stage maps WRITE_FAILED, daemon/recovery retain existing retry ownership.
Adapter produces but does not deliver or duplicate WARN through LogEvents.

<a id="typed-producer-и-точный-счёт"></a>
### Typed producer and exact counts

CHARSET_UNMAPPABLE: WARN, OPERATION, **one occurrence per artifact projection**. Context:
run/artifact/path/charset, affected value occurrences, data rows/header values; no raw
values/unmappable characters.

Unit is **logical CSV value occurrence**, not character/Writer.write call. Multiple bad
characters in one cell count once; repeated value across cells once per cell. Header
counted separately, replacement makes it lossy too. Local CharsetEncoder.canEncode
before CSVPrinter; writer keeps CodingErrorAction.REPLACE. Remove CountingCharsetWriter:
chunks do not align with values, inherently inaccurate.

O(total characters), no second artifact copy. Full rewrite/materialization remains
O(N), optimize under ING-7. Immutable exports outside OBS-2 retain `CodingErrorAction.REPORT`: bytes part
of immutable hash/publication.

<a id="единоличная-доставка-по-трём-путям"></a>
### Sole delivery ownership across three paths

```text
CsvArtifactProjection
  -> ProjectionOutcome(advisory diagnostics)
       |-- oneshot: WriteArtifactsStage -> Envelope -> PipelineRunner -> sink
       |-- daemon: IngestionService -> sink + merged ExtractionResult
       `-- recovery: IngestRunRecoveryService -> sink
```

- **Oneshot:** write stage batch-appends, runner emits once; summary warnings, exit 0.
- **Daemon:** projection stays after markDbCommitted, retaining recovery checkpoint.
  IngestionService emits once through resilient sink, merges original result,
  recomputes completion. Terminal handler only summary, never re-emits occurrences.
- **Startup recovery:** durable run ID, emit before PROJECTION_COMPLETED. No synthetic
  ExtractionResult because driving invocation absent; run ledger state machine, not report store.

<a id="budget-и-terminal-semantics"></a>
### Budget and terminal semantics

Budget bounds high-cardinality ELEMENT/RUN envelope occurrences. Aggregated projection
OPERATION is structurally one per artifact, **never suppressed when retained budget
exhausted**, like synthetic suppression summary. Merge `DiagnosticSummary.total`/severity counts, leave
suppressed unchanged, compute completion afterward.

Do not reevaluate failure policy after projection. Semantics:

| Projection | Canonical | Completion | Driving outcome |
|---|---|---|---|
| No replacement | committed | COMPLETED or prior stricter status | unchanged |
| Replacement | committed | at least COMPLETED_WITH_WARNINGS | exit 0 / success with warnings |
| Hard failure | may already be committed | no result | oneshot WRITE_FAILED; existing daemon/recovery exception/retry |

Replacement terminal ECS outcome success, WARN. Separates durability from representation
quality: canonical data retained, local published projection lossy. Retry/resume after
DB_COMMITTED unchanged, ING-11. OBS-2 does not introduce hard-failure classification
under charset-advisory cover.

<a id="границы-и-реализация"></a>
### Boundaries and implementation

- Diagnostics owns code/immutable summary merge;
- application request/outcome/out-port/exactly-once/completion merge;
- sink-csv detects charset/CSV, produces diagnostics, no policy;
- bootstrap existing resilient sink;
- driving adapters show computed result.

Three atomic commits:

1. `FEATURE: expose artifact projection outcome`;
2. `FEATURE: include projection diagnostics in completion`;
3. `FIX: diagnose lossy CSV projection`.

First two prepare consumers with clean outcome; third activates producer, exact-count
tests, removes CountingCharsetWriter, regenerates catalog, updates docs/closes OBS-2.
Full verify + stand windows-1251 unmappable Unicode smoke.

<a id="реализационное-уточнение-2026-07-15"></a>
### Implementation clarification 2026-07-15

Runner retains generic protocol, invokes pure policy hook after **every** stage including
terminal write. No new post-commit decision: outcome prohibits ERROR/FATAL, hook cannot
reject committed result. No special stage skip flag: complicates ETL without observable
change. Implementation `79e85e8`, `3dda716`, `FIX: diagnose lossy CSV projection`.

Sketch names aligned to executable port conventions: ArtifactProjectionRequest →
**ArtifactProjectionCommand**, ProjectionOutcome → **ArtifactProjectionResult**.
Same fields/invariants; Command/Result suffixes required by ArchitectureTest.ports_are_interfaces
so concrete DTOs in `application.port..` do not weaken “ports are interfaces”.
