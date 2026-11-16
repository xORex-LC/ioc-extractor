> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0014-event-driven-ingest-to-delivery.md) (relative link addresses rebased on relocation).

<a id="0014--event-driven-координация-всей-цепочки-ingest--export--delivery"></a>
# 0014 — Event-driven coordination of the full chain: ingest → export → delivery

<a id="статус"></a>
## Status

**Accepted, not implemented.** Design based on analysis over implemented
[0013](0013-event-driven-coordination.md) (event coordination, S0–S8 + CHANGE_NOTIFY).
**Extends**, without replacing, 0013 across ingest → export → delivery: same doctrine
(events are hints, ledger+reconcile are truth, per-consumer durable ledger, keyed
single-flight, anti-broker), new applications. Establishes a **refactoring/migration
baseline**; no code yet. When/how rationale: [event-coordination.md](../dev/event-coordination.md).

**Update 2026-07-05.** P1/P2 implemented: export recovery emits SliceCompleted
(`49bd68b`), ingest emits CanonicalArtifactsChanged (`d28c74e`), scheduler nudge and
Spring listener connect ingest to export fast path (`e265b0d`, `294b67c`). Follow-up
loop additionally protected against idle states without pending work (`aefb593`).
P3/P4 remain design-state.

**Update 2026-07-06.** Both paths empirically confirmed on test stand (daemon, ECS):
P2 ingest event → nudge check exactly after QP → export → publish within hundreds of ms;
P1 crash simulation (SIGKILL, run reset to AVAILABLE) yielded at restart
`export_recover → export_complete → event_publish → delivery after 140 ms`, before
`Started IocExtractorApplication`. Phase-100 emission reaches executor during startup.
Crash-to-redelivery <12s, versus reconcile tick before P1.

<a id="контекст"></a>
## Context

0013 established doctrine for sync (fetch detection⊥execution + CHANGE_NOTIFY;
publish SliceCompleted + reconcile). Analysis shows ingest → export → delivery is
**only halfway event-driven**:

- **Ingest→export polls revision.** Every pollInterval, DaemonExportScheduler reads
  artifact_revision.changedAt and compares checkpoint through `CadenceSource.isDue(...)`.
  Revision bump is a canonical write-transaction **fact**, learned by polling.
  Export latency depends on polling frequency.
- **Recovery loses fast path.** ExportRunRecoveryService.completeAvailable calls
  ledger.finish to COMPLETED but **emits no SliceCompleted** (no publisher). Post-crash
  delivery waits next reconcile: latency cliff at an undesirable moment.
- **Single-consumer delivery.** SliceCompletedPublishListener alone consumes the fact;
  EXP-3 (firewall EDL, proxy ACL, DNS RPZ, STIX/OpenIOC, appliance push) needs independent fan-out.
- **Purely periodic retention** (DaemonSliceRetentionScheduler + publish-ledger guard).

**Goal:** make the whole chain event-driven under **the same 0013 doctrine**,
**additively retaining backstops**, improving latency, precision and consistency,
without violating anti-broker invariant.

<a id="решения"></a>
## Decisions

<a id="р1-инвариант-эмитить-факт-везде-где-он-становится-истиной"></a>
### P1. Emit a fact everywhere it becomes true

Any path completing a slice, normal export **or recovery**, emits SliceCompleted.
Recovery receives `ControlEventPublisher`, emits `SliceCompleted.from(terminal)` **only at
`AVAILABLE → COMPLETED`**, never SKIPPED (same bytes, nothing to deliver) or FAILED.
Publish-ledger CAS `(sliceId, targetId)` makes recovery-event + later reconcile harmless.

<a id="р2-ingest--export-fast-path-canonicalartifactschanged"></a>
### P2. Ingest → export fast-path (CanonicalArtifactsChanged)

After ingest-run COMPLETED, IngestionService emits CanonicalArtifactsChanged
(affected artifacts + revision + runId correlation). Export reacts through a coordinator
like RemoteFetchDetectionCoordinator, **retaining existing quiet-period/max-cap cadence
as debounce**: event means “activity occurred”, not “export now”. Periodic poll remains
**mandatory**, including non-event cases (coversCurrentPlan: initial export after DB
upgrade, plan drift).

Benefit: **export latency independent of polling frequency**. Poll can become rare,
export still fires after roughly quiet-period, like CHANGE_NOTIFY for fetch.
Affected artifacts allow targeted profiles. Application emission is clean: already
depends on framework-free platform-events.

> **Detailed-design clarification (2026-07-05), P2 below:** payload is **`runId + artifactNames`, no revision value** (claim-check: consumer reads durable revision at
> check time). Coordinator becomes scheduler **nudge() + thin listener**, not a separate
> RemoteFetchDetectionCoordinator-style class. Profile targeting is deferred precision:
> v1 consumer is a doorbell, artifactNames reserved in payload.

<a id="р3-delivery-fan-out-slicecompleted--факт-доставка--n-независимых-consumers"></a>
### P3. Delivery fan-out: SliceCompleted is a fact, N independent consumers deliver

SliceCompleted **remains a fact**, not “send to SMB”. Each channel (SMB, EDL, RPZ,
STIX/OpenIOC, appliance) has a **separate listener + own durable ledger + periodic
reconcile**, not another export if-branch. In-process events are lossy: without a
ledger a consumer loses delivery on event loss. Ledger+reconcile mandatory per channel
(EXP-3, effort L per connector; event part trivial).

**Generalized retention guard is crucial.** Current PublishLedgerSliceRetentionGuard
waits for all **publish targets**. It cannot see new channel ledgers and could delete
before their delivery. Generalize to **all delivery sinks**, common DeliverySink/
DeliveryLedger abstraction, channels report terminal state, any incomplete channel
vetoes deletion. Independent delivery shares retention correctness, solved through
abstraction, not duplication.

> **Detailed-design clarification (2026-07-05), P3:** common abstraction is limited to
> consumer-owned **retention view**, `DeliveryLedger.isTerminal(sliceId, targetId)` +
> DeliveryTarget. Predicate is AND-composition of instances of one generic guard.
> DeliverySink is a channel **role/template**, not Java interface: no polymorphic
> execution consumer. Each channel's saga API remains private.

<a id="р4-retention-periodic-остаётся-авторитетным-event-nudge-отложен"></a>
### P4. Retention: periodic remains authoritative; event nudge deferred

Age/count is **time-driven**, requiring sweep. Periodic retention stays mandatory.
Optional SliceDeliveredToTarget/SlicePublishStateChanged nudge rechecks profile/slice
**only under real disk pressure**: benefit limited to slices already age/count-eligible
and pinned **solely by delivery**. YAGNI until symptoms.

All retain 0013: non-durable hints; consumer-owned ledger+reconcile; business-key
idempotency (slice×target/identity), not eventId; keyed single-flight; anti-broker
(no core queue/DLQ/wire format). No platform-events changes; ArchUnit guards remain.

<a id="детальный-дизайн"></a>
## Detailed design

Pre-implementation slice details, added as developed (P1–P3: 2026-07-05).
Class references reflect branch `module/platform-event/eip-base`.

<a id="р1--recovery-эмитит-slicecompleted"></a>
### P1 — recovery emits SliceCompleted

**Path inventory.** ExportRunRecoveryService alone completes recovered runs; both
call paths reach one singleton, so add emission at **one point**, completeAvailable():

| Path | Call | Mode |
|---|---|---|
| daemon startup | DaemonExportScheduler.start(), phase 100 → RecoverExportUseCase bean (AppConfig lambda under ExportOperationGuard lease) | daemon |
| before every export | ExportService.export() → recovery.recoverIncomplete(), within its own guard | daemon poll / oneshot CLI export |

**Model changes.** Only `ExportRunRecoveryService` (`core/ioc-application`) gains logic:

- New `ControlEventPublisher eventPublisher` field; **both constructors preserved**, delegating to expanded
  overload with NoopControlEventPublisher.INSTANCE. Same ladder as ExportService
  12→13 arguments. Existing full-constructor test fixture remains untouched. Cost:
  deeper telescope, acknowledged **CODE-1** builder-consolidation debt, outside P1.
- In completeAvailable, after `ledger.finish(AVAILABLE→COMPLETED)` and `observer.completed(terminal)`, call
  `eventPublisher.publish(SliceCompleted.from(terminal))` in **local try/catch** (correctness below): same
  durable finish → observer → event order as happy export.

Bootstrap changes only `AppConfig.exportRunRecoveryService(...)` signature, adding publisher
(unconditional `SpringControlEventPublisher` bean from `EventCoordinationConfig`). Reuse application/export SliceCompleted,
correlationId=runId → listener MDC. **Unchanged:** platform-events, SliceCompleted,
publish listener, keyed executor, publish ledger, ArchUnit. `SliceCompleted.from(run)` already rejects
all statuses except COMPLETED.

**Recovery branch semantics:**

| Terminal | Branch | Emit | Why |
|---|---|---|---|
| AVAILABLE→COMPLETED | completeAvailable | **yes** | slice available, delivery needed |
| `… → SKIPPED` | `recoverStartedCandidate()`, same content | no | unchanged bytes |
| `… → FAILED` | `fail(...)` | no | no slice |

**Correctness:**

- *Durable-first*: strictly after ledger.finish, per
  [event-coordination.md](../dev/event-coordination.md) §6.
- *Fire-and-observe is adapter contract, not port type*: `ControlEventPublisher.publish` signature can throw; dev §3
  documents no outward failures, standard Spring adapter catches RuntimeException →
  publishFailed, but no compile-time guarantee and port Javadoc silent. Recovery's
  enclosing try→recoveryException would turn completed work into false RECOVERY_FAILED,
  failing phase-100 startup if adapter violated contract. Local catch makes
  “publication cannot break recovery” local. Intentionally silent with invariant
  comment: framework-free class has no logger; adapter observer is primary error
  channel. Guard is defense in depth, not another channel. ExportService stays
  unguarded: `DaemonExportScheduler.attempt` isolates its throw, without affecting startup.
- *Idempotency*: pair CAS tolerates event+reconcile. No double event per run: terminal
  run no longer appears in `findIncomplete()`.
- *Oneshot*: unconditional publisher/observer records fact; delivery listener absent
  (daemon+sync.publish only), so **no automatic delivery**. Existing export precedent;
  use sync publish manually.
- *Benefit boundary*: closes **only** post-crash latency cliff. Event does not inspect
  remote state; ledger SUCCEEDED is authoritative. Manual remote deletion invisible
  to event **and reconcile**, which compares catalog×ledger rather than remote listing.
  Retains 0011/0013 model, no target self-healing promise.

**Lifecycle (closes P1 question).** Phase-100 event **reaches executor**:

1. Listener and syncKeyedExecutor eager beans (not `@Lazy`), built during refresh before SmartLifecycle.
2. `BoundedKeyedSerialExecutor` fixed ioc-sync-worker pool runs from construction, no lifecycle gate.
3. Multicaster precedes lifecycle processor; synchronous main-thread recovery → listener
   → `executor.submit` → worker, without startup blocking.
4. Phase 100 before `DaemonPublishScheduler` reconcile phase 150; recovered slice delivers in first
   startup seconds, without publish.interval wait.

Only residual risk: bounded admission, 64/endpoint saturation unlikely at startup;
reconcile still repairs. Confirm by startup ECS dispatch→publish observation.

**Tests.** ExportRunRecoveryServiceTest + RecordingControlEventPublisher, following export tests:

- **Exactly one event** for every forward path completing, parameterized ledger-status×FS-state using existing `fixture(status, filesystemState)`:
  STARTED/RECOVERABLE, STARTED/STAGED, STARTED/AVAILABLE, STAGED/STAGED,
  STAGED/AVAILABLE, AVAILABLE/AVAILABLE. Protects single emission point against branch refactors.
- Fields match terminal run, sliceId=runId, manifestSha256 from ledger, also testing SHA propagation.
- **Zero** for skipped, every failed path, empty findIncomplete.
- Throwing publisher preserves recovery and terminal status.
- Existing cases/assertions unchanged via delegating constructors; new parameterized
  cases use same fixture extended with recording publisher.

Confirmed effort **S**, ~3 files (service, AppConfig, test).

<a id="р2--ingest--export-fast-path-canonicalartifactschanged"></a>
### P2 — ingest → export fast-path (CanonicalArtifactsChanged)

**Code facts shaping design:**

- `QuietPeriodCadenceSource.isDue` bases deadline on **durable changed_at**,
  `observedActivity + quietPeriod`, not observation time.
  Event needs no time/revision. Nudge checks at `eventTime + QP`, with `eventTime ≥ changed_at`
  by the `DB-commit → projection → archive → COMPLETED` tail. **V1 deliberately delays by
  run-completion tail**; quiet since durable change has then elapsed, making not-due
  unlikely. MaxCap anchors at first pending observation (pendingSince), hence follow-up loop.
- `IntervalCadenceSource.isDue` ignores activity: **`trigger.type: interval`, the current default, yields no fast-path benefit**.
  P2 benefits quiet-period only. Default switch is an operator decision outside P2.
- bumpRevision only for `inserted > 0`; all-duplicate ingest preserves revision.
  Unconditional COMPLETED hint may be false; durable `activity ≤ checkpoint → clearPending` clears
  pending. Cost: two DB reads.
- Ingest crash recovery blind spot: daemon-only IngestRunRecoveryService completes
  DB_COMMITTED during refresh without re-emitting lost events. Cover through
  **startup nudge**, without adding publisher to recovery.

**Event (application/ingest) and granularity:**

```java
record CanonicalArtifactsChanged(ControlEventMetadata metadata,
                                 String runId,
                                 List<String> artifactNames) implements ControlEvent
```

EVENT_TYPE = "ingest.canonical-artifacts.changed", EVENT_VERSION = 1.
`from(...)` creates withoutCausation metadata, correlationId=runId, mirroring SliceCompleted.
Compact constructor requires text runId, `artifactNames = List.copyOf(...)`, a nonempty list, nonblank
items even while consumer ignores them: cheap contract protection. Resolutions:

- **Per run**, not artifact: N events add noise before consumer coalescing.
- **No revision value**: claim-check reads durable revision through `ArtifactRevisionReader` at check time;
  event snapshot would add stale risk/contract.
- **Names carried, unused by v1 doorbell**: profile gate is two cheap attempt reads.
  Activate targeting only with actual profile growth. Refines earlier precision promise;
  payload preserves possibility, YAGNI now.

**Emission.** Preserve existing IngestionService constructors, delegate with noop,
add publisher overload. End of `processClaimed()` **after `runLedger.markCompleted`**, guarded like P1
(silent catch, adapter publishFailed observes). Covers normal and CLAIMED recovery
through `recover()` → `processClaimed()`. No event for duplicate/FAILED/`reject()`. Canonical truth arises
earlier at markDbCommitted; choose one event per successful run, completion tail
negligible versus QP.

**Consumer: scheduler nudge + thin listener**, resolving coordinator choice.
Neither reuse fetch coordinator (source/transport/health machinery unnecessary) nor
new ExportTriggerCoordinator. “Ask scheduler to check cadence later” belongs to scheduler.

- nudge no-op if `!active` or policy disabled; CAS scheduled flag →
  `executor.schedule(check, policy.delay())`.
  Repeated hint coalesces, **does not reset timer**. Same single-thread executor as
  poll serializes cadence access; only new synchronization primitive is flag.
  Explicit **`ExportNudgePolicy(enabled, delay)`** from AppConfig, `enabled = type == quiet-period`,
  `delay = quiet-period`. Cadence exposes no type; **no instanceof inspection**.
- Check invokes normal `runOnce()`; coversCurrentPlan/`cadence.isDue` stay authoritative against false/
  stale hints. `attempt()` returns **ATTEMPTED / PENDING_NOT_DUE / IDLE / FAILED**, not
  EXPORTED (use case may return unchanged/skipped; due still handled). Pending-not-due
  schedules **follow-up after QP**. Loop lives only while pending. Cadence solely owns
  quiet/max-cap; checks each QP guarantee cap by first-check+maxCap.
- Latency ≤2×QP after last activity (coalescing may miss one check, like fetch debounce).
  Continuous activity: next check after QP+maxCap, **strictly less than `2×QP + maxCap`**
  after first event. Equality `QP + maxCap` only if maxCap multiple of QP, as defaults
  1h/5m. Independent of pollInterval, which can become rare.
- CanonicalArtifactsChangedExportListener: `@EventListener` → MDC run correlation like
  SliceCompletedPublishListener.mdc → `trigger.nudge()`. Depends on bootstrap
  ExportNudgeTrigger `@FunctionalInterface` implemented by scheduler, like `RemoteFetchDetectionTrigger`,
  not concrete scheduler. No heavy publisher-thread work. Single-worker “export” key
  is equivalent to keyed single-flight.
- Bean conditions mirror `daemonExportScheduler`: daemon && export.enabled && two JDBC stores,
  **no sync condition**, since local ingest→export.

**Lifecycle/startup.** After recovery, start nudges for first QP check rather than
pollInterval. **Explicit ordering** avoids its own inactive guard:
recovery → create executor → `active = true` → schedule poll → nudge. Covers lost restart
events and DB_COMMITTED completion by non-emitting ingest recovery. Stop/late nudge
no-throw no-op via flag + RejectedExecutionException guard.

**No new configuration keys.** Delay=trigger.quiet-period, enabled from trigger.type,
existing policy passed explicitly via AppConfig. Separate debounce knob only on demand.

**Correctness** unchanged from P1: durable-first; **adapter observes own failures** (`ControlEventObserver.publishFailed`),
service guard is final barrier for nonconforming adapters, no extra observer/error
channel; durable coversCurrentPlan/isDue revision/checkpoint gates, not eventId;
mandatory non-event poll backstop repairs lost event next tick.

**Tests:**

- IngestionService + recording: exactly one valid run/names event after success,
  CLAIMED recovery; zero duplicate/FAILED-record/claim-fail/extraction-throw/reject;
  throwing publisher preserves ledger and result.
- Scheduler with deterministic executor/clock: delayed check, coalescing, pending-only
  follow-up/no idle loop, cap sequence, startup active order, disabled/stopped no-op.
  **Introduce determinism seam immediately**: package-private injected
  ScheduledExecutorService constructor, fetch-coordinator precedent, avoiding real-time tests.
- Listener captures MDC **inside trigger.nudge**, since scope closes before return.

Effort **M**: event, ingest, AppConfig, scheduler nudge, trigger, listener, tests in
two modules. Tracking **OPS-7**, [KNOWN-ISSUES](../KNOWN-ISSUES.md).

<a id="р3--delivery-fan-out-контракт-deliverysinkdeliveryledger-и-обобщённый-retention-guard"></a>
### P3 — delivery fan-out: DeliverySink/DeliveryLedger contract and generalized retention

**Deferred until first real non-SMB target (EXP-3)**. Record contract now so first
connector does not compromise retention under deadline pressure.

**Code facts shaping design:**

- Guard slot **single/XOR**: `SliceRetentionService` accepts one guard; wiring chooses
  `StandaloneSliceRetentionGuard.INSTANCE` (always true) versus publish ledger. No place for second channel, confirming P3 concern.
- Existing `boolean canDelete(SliceDescriptor)` is ISP-minimal. Veto leaves slice in pool,
  blocked++, maxCount best-effort: ready for composition.
- PublishLedger is **rich saga API**, ~10 methods (ensurePending, CAS transition,
  findRetryable, health). Retention needs only terminal pair fact:
  `SUCCEEDED | ABANDONED`, missing=pin. Generalizing all saga API violates ISP/prematurely
  unifies heterogeneous channels.
- Existing TCK idiom: abstract `IngestionLedgerContractTest` in ioc-application-tck, adapter test dependency.

**Common denominator is consumer-owned retention view only.** Only shared consumer
is retention (export context), therefore:

```java
// application/port/out/export — consumer-owned port (DIP: порт у retention)
interface DeliveryLedger {                 // retention-view, НЕ saga-API
    boolean isTerminal(String sliceId, String targetId);
}
interface DeliveryTarget {                 // общий минимум конфигурации цели
    String targetId();
    String exportProfile();
}
```

- **CompositeSliceRetentionGuard**, application/export: AND over `List<SliceRetentionGuard>`, short-circuit
  first veto, empty allows. Eliminates `StandaloneSliceRetentionGuard`/XOR conditions; zero contributions
  covers standalone, N covers channels.
- **DeliveryLedgerSliceRetentionGuard**, generic replacement of current publish guard:
  profile targets → isTerminal else veto; **one class, N channel instances**, not N
  handwritten guards. PublishTarget implements DeliveryTarget; PublishLedger implements
  isTerminal through find. **No schema/table rename**, publish_ledger remains.
- TCK/LSP contribution contract: false means “my channel not terminal”; unknown
  slice/target false, conservative pin until channel reconciles history.

**Deliberately not generalized:** DeliverySink is **role/template, not interface**;
no polymorphic execution consumer (each channel owns listener/scheduler), so common
interface speculative. Channel-private saga APIs: file push, API push, zone transfer
are not unified in advance. Recommended, not mandatory statuses mirror publish:
PENDING/IN_PROGRESS/SUCCEEDED/FAILED/ABANDONED.

**Channel template**, OCP recipe extending
[event-coordination.md](../dev/event-coordination.md) §6: add only channel-owned parts,
zero edits in export/retention/existing channels:

1. Integration adapter module, one integration per adapter; parent-managed version.
2. Own durable ledger implementing terminal view, slice×target idempotency.
3. Bootstrap SliceCompleted listener → keyed submit only (synchronous multicaster,
   N listeners must avoid heavy work).
4. Own periodic reconcile, catalog×own ledger, following publish scheduler.
5. Composite contribution: `DeliveryLedgerSliceRetentionGuard(ledger, targets)` instance.
6. Own health read model: counts/degradation/queue keys, `syncHealthIndicator` precedent.

**Keyed execution.** Share existing bounded executor, **channelId:endpoint** keys:
serialize within channel/endpoint, parallel across channels; retain bounded admission/
shedding. Revisit worker sizing beyond `max(fetch, publish)` at first connector.
Separate executor only for incompatible latency profiles, not default.

**Configuration resolution.** No one polymorphic list; typed channel sections
(ioc.delivery.<channel>.targets or existing ioc.sync.publish.targets nonbreakingly),
**mandatory common name/export-profile**, plus channel specifics. Common fields define
DeliveryTarget, binding stays per-channel typed.

**Onboarding boundaries.** New channel pins all completed slices until reconcile
catches history: **expected**, bounded by reconcile speed, not a bug. Historical
delivery follows publish semantics; latest-only channel expresses policy in own
saga (e.g. immediately abandon obsolete slices), never weaker guard. Manual remote
cleanup remains invisible as in P1/0011/0013.

**Tests:**

- Composite: empty allows, any false vetoes with short-circuit call order, all true allows.
- Generic guard ports existing publish cases: unknown/nonterminal veto, all terminal allow.
- **DeliveryLedger retention-view TCK** in application-tck: terminal/unknown-conservative
  semantics, run against each channel ledger.
- Channel template tests (listener→submit, idempotent reconcile) at first connector.

**Activation order**, refining migration step 3:

1. Now: paper contract only, no code.
2. First non-SMB target, **step 1 S**: retention refactor, common target/view/generic
   guard/composite, remove XOR; publish first contribution. Self-contained/nonbreaking.
3. **Step 2 L per connector:** implement template.

Effort **S guard refactor + L per connector**, confirming EXP-3; event listener trivial.

<a id="р4--сознательно-не-детализируется"></a>
### P4 — deliberately not detailed

Decision already complete: time policy, authoritative sweep, nudge only disk pressure.
Deletion latency not correctness concern; retention best-effort by design. Source
events arrive only with P3; more design now targets nonexistent code. Activation uses
another retention doorbell like P2. P1–P3 do not block it: channel saga emission point
and publisher seam available.

<a id="отклонённые-альтернативы"></a>
## Rejected alternatives

- **Event-driven CSV projection:** correctness-coupled write→project saga/recovery;
  events trade clean recovery for unnecessary latency. Coalescing belongs to
  **ING-7 delta projection**, not events.
- **Event-driven extraction stages:** in-process transformation, not cross-context
  coordination; no gain, extra indirection.
- **“File appeared” control event:** SI inbound already supplies edge events; do not duplicate framework.
- **Delivery if-branches in export / SliceCompleted as SMB command:** breaks fact≠intent,
  Event Notification/OCP, couples export to every sink.
- **Entirely event-driven retention:** age/count time-based, only sweep enforces.
- **Outbox/external broker now:** deferred **OPS-4** seam; in-process + consumer ledgers
  sufficient until interprocess delivery.

<a id="следствия-включая-migrationrollout"></a>
## Consequences (including migration/rollout)

- **Additive/nonbreaking:** each fast path overlays existing backstop; delete nothing.
  Incremental, self-contained slices.
- **Migration order:**
  1. **P1 S**, recovery event: smallest, closes post-crash cliff, completed transition only;
     detailed design above.
  2. **P2 M**, canonical change + scheduler nudge, no separate coordinator; backstop
     unchanged, polling-independent latency; detailed design, **OPS-7**.
  3. **P3 L per connector**, first real non-SMB target: DeliverySink + generalized guard;
     contract detailed above, guard S + connector L, **EXP-3**.
  4. **P4**, retention nudge deferred until disk pressure.
- **Placement:** events beside their facts in application (canonical change artifact/
  ingest, delivery/retention own contexts); platform-events unchanged.
- **Health:** each consumer adds ledger read model/reconcile like publish.
- **Docs:** extends 0013; update [event coordination](../dev/event-coordination.md)
  and [sync](../dev/sync.md) on implementation. [Known issues](../KNOWN-ISSUES.md):
  P2 gets ID, P3 EXP-3, P4 disk-pressure trigger.

<a id="открытые-вопросы"></a>
## Open questions

- **P1 lifecycle:** does phase-100 event reach ready executor? **Resolved by code
  analysis 2026-07-05: yes**, eager listener/executor, pool alive from construction,
  reconcile phase 150. Startup ECS confirmation remained. **Empirically confirmed
  2026-07-06**: delivery 140ms after recovery emission before startup completed
  (Status update).
- **P2 granularity/revision/coordinator:** **resolved 2026-07-05**: per run, no revision
  (claim-check), neither reused nor separate coordinator, scheduler's own worker nudge
  + thin listener behind trigger. Detailed P2 above.
- **P3 contract/terminal aggregation/config uniformity:** **resolved 2026-07-05**:
  consumer-owned retention view only, terminal ledger + target, AND generic guard
  instances, unknown conservatively pins; Sink role not interface; typed per-channel
  config with name/export-profile common minimum. Detailed P3 above.
- Create known-issue tracking immediately? **Resolved 2026-07-05**: **OPS-7** created
  in [KNOWN-ISSUES](../KNOWN-ISSUES.md), linking detailed P2 design.

<a id="план-реализации-р1р2-по-срезам"></a>
## P1/P2 implementation slices

Execution checklist. Commit P1 separately; P2 event/emission first, consumer/nudge/wiring
next. Preserve 0013: non-durable hints, Spring only in-process adapter/fan-out, heavy
work in consumer-owned executor/scheduler, mandatory backstop retained.

<a id="срез-1--р1-recovery-эмитит-slicecompleted-выполнен-49bd68b"></a>
### Slice 1 — P1: recovery emits SliceCompleted (done: 49bd68b)

**Goal:** give recovered completed slices the same post-crash delivery fast path as normal export.

**Code changes:**

- `core/ioc-application/.../export/ExportRunRecoveryService.java`:
  - publisher field;
  - preserve both constructors with noop delegation, new full overload;
  - completeAvailable: ledger.finish completed → observer.completed → publish factory;
  - local try/catch invariant comment, standard adapter observes errors, guard defends
    recovery from nonconforming adapter.
- `bootstrap/ioc-app/.../AppConfig.java`: publisher argument in
  `exportRunRecoveryService(...)` bean signature and new overload call.

**Tests**, `core/ioc-application/.../export/ExportRunRecoveryServiceTest.java`:

- Recording publisher fixture;
- exactly one for STARTED/RECOVERABLE, STARTED/STAGED, STARTED/AVAILABLE,
  STAGED/STAGED, STAGED/AVAILABLE, AVAILABLE/AVAILABLE;
- profile, `sliceId = runId`, sliceName, manifestSha256, `metadata.correlationId = runId`;
- zero skipped/failed/empty incomplete;
- throwing publisher preserves completed status/recovery success.

**Local check:**

```bash
./mvnw -pl core/ioc-application -am test \
  -Dtest=ExportRunRecoveryServiceTest,ExportServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary:** self-contained `P1 recovery emits SliceCompleted`.
Keep separate from P2: small impact, immediate value.

<a id="срез-2--р2a-canonicalartifactschanged-и-эмиссия-из-ingest-выполнен-d28c74e"></a>
### Slice 2 — P2a: CanonicalArtifactsChanged and ingest emission (done: d28c74e)

**Goal:** application-level fact: completed ingest may have changed canonical artifacts.
No consumer yet.

**Code changes:**

- `core/ioc-application/.../ingest/CanonicalArtifactsChanged.java`:
  - record(metadata, String runId, List<String> artifactNames) implements ControlEvent;
  - EVENT_TYPE="ingest.canonical-artifacts.changed", EVENT_VERSION=1;
  - nonnull metadata, nonblank runId, List.copyOf, nonempty list/nonblank items;
  - factory `from(String runId, List<String> artifactNames, Instant occurredAt)`, deterministic
    canonical-artifacts-changed:<runId> event ID, `correlationId = runId`, `causationId = null`;
  - no hidden `Instant.now()`:  explicit time. Additional factory from terminal IngestRun
    durable updatedAt allowed if introduced; `RunLedger.markCompleted(...)` currently returns void, so injected
    Clock is primary.
- `core/ioc-application/.../ingest/IngestionService.java`:
  - publisher and `Clock clock` fields;
  - preserve constructors, noop + `Clock.systemUTC()` delegation;
  - new overload receives both;
  - guarded event after `runLedger.markCompleted(run.runId())`;
  - use durable `run.artifacts()` or `sourceSinks.artifactNames()`, preferring normalized durable snapshot.
- `bootstrap/ioc-app/.../AppConfig.java` passes publisher and existing Clock bean.

**Tests**, `core/ioc-application/.../ingest/IngestionServiceTest.java`:

- exactly one valid event on success and CLAIMED recovery via processClaimed;
- no duplicate/existing FAILED/claim-fail/extraction failure/projection failure/reject event;
- throwing publisher preserves final ledger/source statuses and successful result.

**Local check:**

```bash
./mvnw -pl core/ioc-application -am test -Dtest=IngestionServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit:** `P2a emit CanonicalArtifactsChanged`. Event without listener is an
acceptable harmless-hint intermediate state.

<a id="срез-3--р2b-daemonexportschedulernudge-выполнен-e265b0d-fix-aefb593"></a>
### Slice 3 — P2b: DaemonExportScheduler.nudge() (done: e265b0d, fix aefb593)

**Goal:** consumer-owned asynchronous boundary; event requests earlier durable cadence
check, Spring listener never exports.

**Code changes:**

- `bootstrap/ioc-app/.../ExportNudgeTrigger.java`: package-private FunctionalInterface void nudge().
- `bootstrap/ioc-app/.../ExportNudgePolicy.java`: `enabled + delay`, positive when enabled;
  disabled can retain `Duration.ZERO` or configured delay.
- `bootstrap/ioc-app/.../DaemonExportScheduler.java`:
  - implements trigger, adds `ExportNudgePolicy nudgePolicy` and AtomicBoolean nudgeScheduled;
  - package-private **Supplier<ScheduledExecutorService> factory**, not ready executor:
    each lifecycle start needs a new pool after stop, preserving SmartLifecycle restart;
  - default current single-thread ioc-export-scheduler;
  - start: recovery → `nudgeScheduled.set(false)` → create executor → `active = true` → poll → nudge;
  - nudge: no-op inactive/disabled/`executor == null`; CAS `nudgeScheduled false→true`;
    `executor.schedule(this::runNudgedCheck, nudgePolicy.delay())`; swallow RejectedExecutionException, reset flag;
  - `runNudgedCheck()` resets flag, invokes shared nonoverlap path; pending-not-due or BUSY
    schedules follow-up; **FAILED does not**: retry remains periodic, avoiding QP storm;
  - ordinary `runOnce()` poll creates no follow-ups;
  - `attempt(plan)` outcome ATTEMPTED/PENDING_NOT_DUE/IDLE/FAILED;
  - ATTEMPTED after use-case call even if unchanged/skipped; then `cadence.completed()` as before.
- `bootstrap/ioc-app/.../AppConfig.java` derives policy from `props.export().trigger()`:
  `enabled = normalizedTriggerType.equals("quiet-period")`, `delay = trigger.quietPeriod()`.
  Normalize exactly like CadenceSources.create (`trim().toLowerCase(Locale.ROOT)`), avoiding
  Quiet-Period enabling cadence but silently disabling nudge. Pass policy to scheduler.

**Tests**, `bootstrap/ioc-app/.../DaemonExportSchedulerTest.java`:

- controllable executor factory;
- delayed check/coalescing, disabled no-op;
- stop+late nudge safe;
- `stop() → start() → nudge()` works, scheduled flag not permanent latch;
- startup schedules after active;
- pending follow-up, idle none;
- busy overlap retains wakeup through follow-up;
- failed none, poll retries;
- cap through QP check series.

**Local check:**

```bash
./mvnw -pl bootstrap/ioc-app -am test -Dtest=DaemonExportSchedulerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit:** `P2b add export nudge scheduler`, optionally combined with slice 4 if smooth;
keep separate if concurrency complications arise.

<a id="срез-4--р2c-listener-и-spring-wiring-выполнен-294b67c"></a>
### Slice 4 — P2c: listener and Spring wiring (done: 294b67c)

**Goal:** thin listener connects canonical-change event to nudge.

**Code changes:**

- `bootstrap/ioc-app/.../CanonicalArtifactsChangedExportListener.java`:
  - EventListener receives fact;
  - MdcScope event fields/runId;
  - `ControlEventObserver.dispatching(event, HANDLER)` before nudge, shared observability like `SliceCompletedPublishListener`/`RemoteChangeFetchListener`;
  - `ExportNudgeTrigger.nudge()` only;
  - dispatchFailed usually unnecessary: nudge no-throw, handles late-stop/rejection;
  - no heavy work/DB/export in listener.
- `bootstrap/ioc-app/.../AppConfig.java` bean mirrors
  `daemon && export.enabled && service/dataframe jdbc`, no sync conditions.
- `bootstrap/ioc-app/.../README.md` reference entries for listener, trigger, policy.

**Tests:**

- New listener test dispatch→nudge;
- inspect MDC inside fake trigger, scope closed on return;
- avoid unnecessary AppConfig/condition context tests if existing coverage indirectly
  checks conditions.

**Local check:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=CanonicalArtifactsChangedExportListenerTest,DaemonExportSchedulerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit:** `P2c wire ingest changed listener`; may combine with P2b if reviewable.

<a id="срез-5--документы-и-закрытие-tracking"></a>
### Slice 5 — docs and tracking closure

**Goal:** documentation moves from design to shipped state after code.

**Documentation changes:**

- `docs/dev/event-coordination.md`: implemented P1/P2, synchronous Spring dispatch, consumer-owned async boundary.
- `docs/dev/sync.md` only if delivery/reconcile needs P1 mention; keep local ingest→export in its owning doc.
- `core/ioc-application/src/main/java/com/iocextractor/application/export/README.md`: recovery also emits at completed transition.
- `core/ioc-application/src/main/java/com/iocextractor/application/ingest/README.md`: canonical-change post-completion fact.
- `docs/KNOWN-ISSUES.md`: OPS-7 open→closed with commit/stage, retain historical row per file policy.
- `docs/ADR/0014-event-driven-ingest-to-delivery.md`: append dated shipped status, **without rewriting historical decision**;
  mark slices 1–4 done so plan no longer implies all code absent.

**Final check:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Recommended commits:**

1. `P1 recovery emits SliceCompleted`
2. `P2a emit CanonicalArtifactsChanged from ingest`
3. `P2b/P2c add export nudge fast-path`
4. `Docs mark ADR 0014 P1/P2 shipped`

In one pass, slices 3/4 may combine, both bootstrap and together form end-to-end path.
Keep 1/2 separate: different bounded contexts/test surfaces.
