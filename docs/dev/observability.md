# Diagnostics and operational observability

[Русская версия](ru/observability.md)

Diagnostics describe processing outcomes and can affect completion/failure policy.
Operational logs describe execution, I/O, lifecycle, retry and coordination.
Exceptions carry failures across boundaries; they replace neither model.
This capability explains their IOC composition. Reusable API guarantees and
standalone examples belong to the linked library guides.

## Runtime flow

```text
stage outcome -> DiagnosticBatch (retained detail + exact counts)
  -> run accumulation + delivery of newly retained occurrences
  -> failure-policy checkpoint -> continuation or rejection
  -> ExtractionResult + DiagnosticSummary when processing completes

stage exception -> owning diagnostic boundary -> diagnostic delivery + failure

runtime action -> typed LogEvent / MDC scope -> SLF4J -> appender / encoder
Diagnostic -> LoggingDiagnosticSink -> ordinary typed operational record
```

The runner processes an append-only stage delta, not the whole prior sample again.
A diagnostic occurrence belongs either in the outcome or in `DiagnosticException`,
not both. Logical delivery at this boundary is not durable log delivery or a
promise that every observed occurrence is retained and emitted.

## Ownership boundaries

| Owner | Responsibility |
|---|---|
| `platform-diagnostics-api` | Framework-free values, result/policy, bounded accumulation and synchronous sinks |
| `platform-diagnostics` | IOC codes, metadata, exception and renderer |
| `platform-logging-slf4j` | Reusable typed scalar events, normalization, restoring MDC and explicit masks |
| `platform-observability` | IOC fields/actions, facades and ETL logging observer |
| `platform-diagnostics-logging` | Diagnostic-to-log bridge and IOC context redaction |
| `bootstrap/ioc-app` | Composition, decision TRACE, health/metrics, encoder and logging destinations |

Domain returns pure decisions without depending on these modules; application
creates diagnostics and decision observations. Generic libraries select no IOC
catalog, failure checkpoint, logging provider or service recovery policy.
[ADR 0044](../ADR/0044-reusable-diagnostics-contract.md) and
[ADR 0045](../ADR/0045-reusable-slf4j-logging-contract.md) record this boundary.
Module structure belongs to [diagnostics](../../platform/platform-diagnostics-api/README.md)
and [logging](../../platform/platform-logging-slf4j/README.md) references;
consumer contracts belong to the [diagnostics guide](../guides/libraries/diagnostics.md)
and [logging guide](../guides/libraries/logging-slf4j.md).

## Diagnostic identity and presentation

`DiagnosticCode` supplies stable ID, default severity and independent impact.
IOC's `IocDiagnosticCode` adds category, message key and default template.
A generic code renders as its literal ID with no IOC category; the bridge hides
an ambient category too. The current code inventory is generated in
[DIAGNOSTICS-CATALOG.md](../DIAGNOSTICS-CATALOG.md).

Producers supply code and structured context. `DiagnosticRenderer` owns message
presentation; it does not change the underlying diagnostic or Throwable.
Code equality and metadata must be stable. Occurrences are counted independently;
equal diagnostics do not become one occurrence through value equality.

Impact (`ELEMENT`, `RUN`, `OPERATION`) belongs to code metadata, not severity.
It determines budget treatment, not permission to skip a row, commit or retry.
Saga diagnostics report a final fact after retries/state transition; durable
ledger state remains the recovery authority.

## Budgets, policy and completion

Stage collectors retain a bounded sample plus rejecting signals; the run keeps
at most its configured ELEMENT/RUN budget, replacing weaker samples when needed.
Both retain every OPERATION occurrence outside that budget. Exact counts survive
compaction, but memory, context size and total delivery calls are not globally
bounded by the sample limit. See the library guide for exact stage/run algorithms.

At run budget 2, WARN A, ERROR B, FATAL C leave `[C, B]`: replacement preserves the
sample position, not encounter order. Built-in policy can select C instead of the
earliest rejecting B. A custom rule needing every code must see observations
before compaction. Match stage/run budget assumptions when composing a pipeline.

`PIPELINE.DIAGNOSTICS_SUPPRESSED` is a synthetic display marker. Merge real batches
and their summaries, never the marker as a new occurrence. The runner emits stage
deltas through the retained-delivery callback and emits the suppression summary
at the appropriate terminal boundary. Preparation can defer that final marker to
continuation/cancellation. Repeated snapshot reads do not deliver anything.

| Observed severity | `fail-fast` | `collect-and-continue` |
|---|---|---|
| TRACE / DEBUG / INFO / WARN | Continue | Continue |
| ERROR | Stop | Continue |
| FATAL | Stop | Stop |

Policy evaluation alone neither throws a product exception nor cancels work;
the orchestrator enforces the decision before the relevant durable effects.
A stopped run need not return a completed result. For a returned result,
`CompletionStatus` uses the full `DiagnosticSummary`, including suppressed counts:
ERROR/FATAL produces `COMPLETED_WITH_ERRORS`, otherwise WARN produces
`COMPLETED_WITH_WARNINGS`, otherwise `COMPLETED`. The extraction CLI returns `3`
for a completed-with-errors result; command failure is a separate outcome.

Collecting errors therefore does not mean success, and logging fewer records
does not change policy or completion. Document writes commit per artifact; a
later artifact/projection failure does not roll back earlier writes. See
[processing](processing.md) for checkpoint and partial-effect boundaries.

## Delivery and observer failure

Default severity-to-log-level mapping is stable:

| Diagnostic severity | Log level |
|---|---|
| `FATAL`, `ERROR` | `ERROR` |
| `WARN` | `WARN` |
| `INFO` | `INFO` |
| `DEBUG` | `DEBUG` |
| `TRACE` | `TRACE` |

Bootstrap wraps `LoggingDiagnosticSink` in `ResilientDiagnosticSink`. It calls
logging once and reports a runtime delivery failure through the fallback callback;
there is no durable queue, timeout or automatic retry. Runtime fallback failure
is contained; `Error` propagates. The log record's absence is not a processing
success and does not establish that a committed operation was rolled back.

Raw batch delivery callbacks may fail after partial mutation; replaying the whole
batch can double-count or redeliver its accepted prefix. Supplied pipeline
observers/scopes and bare log builders are not all made resilient by this wrapper.
Provider or cleanup failure can still propagate at those call sites. Decision
TRACE and managed-import observation have their own RuntimeException containment;
it does not promise recovery from an arbitrary fatal `Error`.

Durable diagnostic occurrence reports/quarantine remain the OBS-4 seam in
[KNOWN-ISSUES.md](../KNOWN-ISSUES.md). A file appender or collecting sink is not
that report model and owns no business transaction.

## Typed logging and correlation

IOC's `LogField`, `EventAction`, `EventOutcome` and `LogEvent` bind local catalogs
to the reusable API. `event.action` is a stable machine-readable operation;
`message` is human-readable text. The generated [LOGGING-CATALOG.md](../LOGGING-CATALOG.md)
owns the field/action inventory. Project fields stay in `ioc.*`; ECS fields retain
their meanings. A run ID is not an APM `trace.id` or `transaction.id`.

The driving boundary creates run identity; daemon ingestion uses its durable
`ingest_run` identity. Pipeline run/stage scopes restore ambient MDC; terminal
diagnostics retain the run without a false final-stage value. MDC carries STRING
context, while event-local STRING/LONG/BOOLEAN values use typed SLF4J pairs.
Field validation occurs even if the selected logging level is disabled.

A field key identifies one type within an event, including null declarations.
On enabled emission, non-null local values temporarily hide exact same-key MDC
strings and restore them afterwards. Null omits a local value without hiding
ambient MDC; use an explicit hide scope when omission must suppress that copy.
Parent/child path conflicts and reserved metadata remain the service's schema
responsibility. There is no global field registry.

MDC scopes belong to one thread and close in LIFO order. Create/populate a worker's
scope inside that worker from explicit command metadata; context does not transfer
automatically. Acquire the scope before populating it so cleanup is available if
population fails. Provider failure can prevent complete restoration.

One action can describe multiple lifecycle records: start can carry
`event.outcome=unknown`, terminal success/failure can carry `event.duration`.
For example, `ingest_recover` observation does not replace
`INGEST.RECOVERY_FAILED` or `INGEST.STATE_TRANSITION_CONFLICT` diagnostics.
Control-event publish/dispatch and keyed admission remain operational signals;
they do not require an artificial processing category `EVENTS`.

## Logging modes and destinations

The shipped configuration maps `ioc.observability.mode` to active Spring profiles;
`ioc.runtime.mode` separately selects the application lifecycle. Installed daemon
configuration sets the intended combination. Selecting runtime mode alone is not
proof that a particular appender/profile was loaded.

The `daemon` logging profile writes nested ECS JSON through the rolling appender
under `logging.file.path` and also writes console output. Other profiles use the
console path. The console and file need not have the same format. Rotation and
retention belong to [logback-spring.xml](../../bootstrap/ioc-app/src/main/resources/logback-spring.xml),
not a second policy inventory here. Observe actual destination bytes and encoder
status, not merely a successful logger call.

## ECS wire representation

Logical dotted fields materialize as nested JSON objects in the daemon file:

```json
{
  "ecs": {"version": "8.11"},
  "event": {
    "action": "app_start",
    "outcome": "success",
    "dataset": "ioc-extractor"
  }
}
```

The older flat representation used paths such as:

```json
{
  "ecs.version": "8.11",
  "event.action": "app_start",
  "event.outcome": "success"
}
```

| Logical field | Old flat query | Current nested query |
|---|---|---|
| `ecs.version` | `jq '.["ecs.version"]'` | `jq '.ecs.version'` |
| `service.name` | `jq '.["service.name"]'` | `jq '.service.name'` |
| `event.action` | `jq '.["event.action"]'` | `jq '.event.action'` |
| `event.outcome` | `jq '.["event.outcome"]'` | `jq '.event.outcome'` |
| `ioc.run.id` | `jq '.["ioc.run.id"]'` | `jq '.ioc.run.id'` |

`event.outcome` is not a newly introduced field: its physical path changed.
Typed migration also changes scalar types without renaming logical keys. Queries,
collectors and dashboards must account for both shape and type compatibility.

`IocEcsStructuredLogEncoder` contributes configured `event.dataset` as a key/value
pair before delegating nested rendering to Boot, then restores the source event.
This avoids a competing static JSON-add member. Unlike the strict standalone
[Boot recipe](../guides/libraries/logging-slf4j-boot.md), the IOC encoder delegates
non-mutable events and events already carrying a dataset pair to the standard
encoder; it does not install all the recipe's rejection guards. Catalog ownership
and supported producers matter. Raw SLF4J/MDC writes can still introduce conflicts.
With a Throwable, let Boot own `error.*`; a type-only record can supply `error.type`.
A conflicting encoder input can drop a record and report an appender error without
throwing to the producer. The representative service contract is
`LogbackConfigurationIT`; the consumer recipe has its own qualification scope.

## Query migration

Control-event type from `LoggingControlEventObserver` lives in `ioc.event.type`,
physically `/ioc/event/type`; migrate former `event.type` queries. The old key is
not duplicated and no ECS type/category array support was added.

Skipped `LegacyLedgerImporter` and up-to-date `SqliteUserVersionSchemaMigrator`
records use `ioc.operation.disposition=skipped`. Replace former
`event.type=skipped` queries. Those two DEBUG records omit
`event.outcome=unknown`, since they do not assert that outcome. Actions
`ledger_import`/`schema_migrate` remain stable.

`LocalImportChangeSignalSource` passes its failure Throwable without an explicit
`error.type`, allowing Boot to form one error object with stack trace. Type-only
errors still work. `LoggingMigrationIT` executes the real producer branches and
checks actual JSON lines and encoder/appender statuses.

## Disclosure and redaction

INFO does not enumerate raw IOC. Raw DEBUG/TRACE values require an explicitly
permitted producer contract; per-item decision TRACE additionally requires both
`ioc.observability.per-item-trace-enabled` and the logger's TRACE level.

IOC's `RedactingDiagnosticContextFormatter` fingerprints the registered raw
`indicator`/`value`/`item` context values at INFO/WARN/ERROR/FATAL. At DEBUG/TRACE
it applies the lexical URL-component sanitizer. Embedded copies of those known
raw values in other context entries are handled too. The helper removes recognized
URL user-info/query content, not arbitrary secrets, paths or fragments. It is
not a URL parser or an automatic scrubber for every logging channel.

The formatter transforms rendered context only. Original diagnostics, free-form
messages and Throwable are not automatically rewritten. `LoggingDiagnosticSink`
passes the original cause to the backend, which can include exception messages,
causes and suppressed exceptions in its stack trace. Producers own disclosure of
all those channels; do not infer full redaction from a sanitized human message.
Generic codes render as literal IDs, not as templates exposing context.

Operational fields exclude transport host/share/username/password. Managed-import
reports, health and INFO records exclude raw CSV cells, producer filename/path,
snapshot digest and contract-sensitive payload. They use safe delivery/sequence,
counts and codes. Masks/hides affect only selected copies; inspect complete output
and fallback failure logging when qualifying disclosure.

## Runtime observations

| Contributor | Meaning / owning capability |
|---|---|
| `ingestionLifecycle` | Recovery barrier and running intake; [ingestion](ingestion.md) |
| `dataProcessingCapacity` | Pending bytes/age/phases, blocked dispatch and writer/workspace pressure; ingestion |
| `lifecycle` | Admission/activation, safe clock and convergence; [record lifecycle](canonical-record-lifecycle.md) |
| `dataframeImport` | Recovery phase, backlog and safe head state; [managed import](dataframe-import.md) |
| `sync` | Source/target/watch observations and durable publication backlog; [sync](sync.md) |
| `smbTransport` | Application-owned resource leases, planned demand and capacity failures; sync |

Health reads do not perform work or prove every source/transaction succeeded.
Clock/convergence or bounded retry can be DEGRADED; unsafe clock, failed recovery,
blocked dispatch or durable failed delivery can require DOWN under their owners'
policies. `dataframeImport` reading can fail for a pending head with a null retry
deadline (`OPS-10`); an observation failure is not a rollback signal. See known issues.

SMB metrics are `ioc.smb.connections.active`, `ioc.smb.sessions.active`,
`ioc.smb.tree.connections.active`, `ioc.smb.session.opens`,
`ioc.smb.operation.failures` and `ioc.smb.resource.exhaustions`. Labels are bounded
logical `endpoint`, `role` and, for opens only, `outcome`. They exclude remote
host/share/path/username/source. Established application usage and planned demand
are not server-wide limits/headroom; capacity degradation clears on confirmed
success for the same internal owner, without making that owner a metric label.

Lifecycle actions `lifecycle_admission`, `lifecycle_reconcile`,
`lifecycle_projection`, `lifecycle_retention` use aggregate data, not per-record IOC.
Successful empty five-second checks remain silent; failed work retains diagnostics
and ERROR. Reconcile logs INFO when records expire; projection logs actual/pending work.

Managed import observes `import_start`, `import_claim`, `import_stage`,
`import_promote`, `import_complete` after their durable checkpoints and
`import_retry` after retry scheduling. Recovery/nonempty retention are aggregate;
empty checks stay silent. Logging uses delivery/sequence/source, state/outcome,
contract id/version, counts, artifact names and safe error type/code. Terminal
records expose `ioc.import.disposition` (`terminal`/`quarantine`) and, for a safely
classified parse failure, value-free `ioc.import.failure_reason`. Candidate tokens,
locators, filenames/paths, digests, raw rows and exception messages do not enter
that adapter. The owning failure boundary emits the diagnostic once; the typed
operational event does not duplicate it.

## Extension, sources and maintenance

Add a diagnostic code with a real producer; catalog reference ratchets reject
orphan constants. Add a log field/action with actual JSON type, collision and
redaction checks. New outputs implement `DiagnosticSink`; durable reporting needs
an explicit occurrence identity/store. APM must supply real tracing semantics.

- [Runner](../../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/PipelineRunner.java)
  owns stage deltas, budgets and policy enforcement.
- [Composition](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
  owns renderer/sink/tracer wiring;
  [bridge](../../platform/platform-diagnostics-logging/src/main/java/com/iocextractor/observability/diagnostics/LoggingDiagnosticSink.java)
  owns category and severity mapping.
- [IOC encoder](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocEcsStructuredLogEncoder.java),
  service JSON tests and standalone recipe tests own their different backend paths.
- [Defaults](../../bootstrap/ioc-app/src/main/resources/application.yml) and the
  generated catalogs own exact configuration/vocabulary inventories.

Update this document, its translation and affected guides when delivery, budgets,
completion mapping, correlation, disclosure or wire format changes. Keep library
versions and release evidence in their guides/permanent records.

## Related documents

- [Event coordination](event-coordination.md) — operational hints and backstops.
- [Security engineering](../SECURITY-ENGINEERING.md) — logging security policy.
- [ADR-0017](../ADR/0017-diagnostics-first-class-outcome.md) — diagnostic outcomes.
- [ADR-0018](../ADR/0018-typed-ecs-structured-logging.md) — typed logging.
- [ADR-0019](../ADR/0019-spring-boot-4-nested-ecs.md) — nested ECS representation.
