> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0018-typed-ecs-structured-logging.md) (relative link addresses rebased on relocation).

<a id="0018--типизированные-ecs-structured-logs-event-fields-вне-mdc"></a>
# 0018 — Typed ECS structured logs: event fields outside MDC

<a id="статус"></a>
## Status

**Accepted 2026-07-16, not implemented.** Defines closure of **OBS-D3** in
[KNOWN-ISSUES](../KNOWN-ISSUES.md): numeric and boolean operational-log fields
must retain their JSON types through to the ECS file instead of becoming strings
at the SLF4J MDC boundary.

> **Partially superseded 2026-07-21:** [ADR-0019](0019-spring-boot-4-nested-ecs.md)
> replaces only the exact Spring Boot baseline, static `event.dataset` addition
> and physical ECS JSON representation (flat dotted keys → nested objects).
> Typed field taxonomy, MDC/event-local split, scalar types, actions, outcomes,
> redaction and other decisions remain.

This ADR refines and partially supersedes [0007](0007-logging-observability.md):

- Decision 4 remains for **ambient correlation context**, but no longer applies
  to event-local structured fields.
- The `co.elastic.logging:logback-ecs-encoder` consequence is replaced by built-in
  Spring Boot ECS structured logging.
- `event.dataset` remains an event field, not a correlation key; for the only
  current dataset it is statically `ioc-extractor`. Multiple datasets require
  a separate decision and removal of the static member to avoid duplicate keys.
- Run/source/stage correlation truth remains in `Envelope.meta`/the driving
  boundary; MDC remains only its derived transport representation.

[0017](0017-diagnostics-first-class-outcome.md) is not superseded: diagnostics
as first-class outcomes, severity→level mapping, redaction and resilient delivery
are unchanged. This ADR implements the typed logging channel deferred there.

Related published documents:

- [observability.md](../dev/observability.md);
- [LOGGING-CATALOG.md](../LOGGING-CATALOG.md);

<a id="контекст"></a>
## Context

<a id="текущая-цепочка-теряет-тип"></a>
### The current chain loses types

`LogEvent` accepts `Object`:

```java
LogEvents.info(log)
        .field(LogField.IOC_ROWS, 58)
        .durationNanos(18_324_056L)
        .message("stage completed")
        .log();
```

At input, `58` is an `Integer` and duration a `Long`. Then:

```text
LogEvent.fields: Map<String, Object>
  -> LogEvent.scope()
  -> MdcScope.put(key, value)
  -> MDC.put(key, String.valueOf(value))
  -> EcsEncoder.serializeMDC(...)
  -> JSON string
```

The resulting daemon file contains:

```json
{
  "event.duration": "18324056",
  "ioc.rows": "58",
  "ioc.sync.shed_to_reconcile": "true"
}
```

The cause is structural, not a local encoder-setting mistake:

1. SLF4J MDC's contract is `Map<String, String>`.
2. Current `logback-ecs-encoder 1.8.0` serializes MDC through `String.valueOf(...)`
   and JSON strings.
3. Encoder `additionalFields` are static for the whole appender, not per-event transport.
4. A custom protected `addCustomFields(...)` hook would require maintaining a
   framework-specific serializer.

<a id="долг-вырос-после-d1"></a>
### Debt grew after D1

When OBS-D3 was recorded, `event.duration` and `ioc.rows` were the main visible
fields. After [0017](0017-diagnostics-first-class-outcome.md), terminal completion
publishes diagnostic total/suppressed and six severity counts. Sync/export/storage
also publish queue depth, file counts, revisions, schema versions and boolean
admission outcomes.

`LogField` currently has 58 fields, a substantial portion numeric or boolean:

- `event.duration`;
- `ioc.span.start`, `ioc.span.end`;
- `ioc.rows`;
- schema/migration/identity versions;
- export revision and control-event version;
- sync files/queue/abandoned counts;
- diagnostic total/suppressed and per-severity counts;
- `ioc.sync.shed_to_reconcile`.

Two JDBC producers additionally bypass taxonomy through public
`LogEvent.field(String, Object)`:

- `ioc.legacy_import.*`;
- `ioc.storage.sqlite.*`.

Fixing today's known numeric keys alone therefore does not close the class of
problem: a new producer could publish another stringly numeric field.

<a id="проявление-на-elasticsearch-boundary"></a>
### Manifestation at the Elasticsearch boundary

Before an Elasticsearch consumer exists, a string number is barely noticeable:
JSON is valid, Python reads the value and text search works.

After Elasticsearch integration, two modes are possible:

1. With a preinstalled numeric mapping, a quoted number may be coerced because
   Elasticsearch enables string→number coercion by default. This is not a
   contract: coercion can be disabled, a malformed value rejects the document,
   and `_source` still preserves the original representation.
2. With dynamic mapping, the first document containing a string creates a
   `keyword`/string mapping. A later numeric document conflicts with it;
   `avg`, `sum`, percentiles, numeric sorting and range queries remain unavailable
   without rollover/reindex.

Do not delegate correction to each consumer's ingest pipeline or runtime fields.
The producer must emit the correct JSON scalar type.

<a id="движущие-силы-и-ограничения"></a>
## Drivers and constraints

The decision must satisfy all of these requirements:

1. Preserve `String`/integral `long`/`boolean` from `LogEvent.field(...)` to JSON
   without heuristic reparsing.
2. Keep run/source/stage/correlation in MDC so ordinary Spring/JDBC/adapter logs
   not using `LogEvent` receive those fields.
3. Keep Spring Boot, Logback and JSON serializers outside domain/application;
   add no framework dependency to `platform-observability`.
4. Make field type part of the generated taxonomy and failing-test contract.
5. Disallow arbitrary unregistered structured keys.
6. Do not emit duplicate JSON keys from MDC and event-local transport.
7. Preserve correctness with Logback `AsyncAppender`: event-local data belongs
   to `ILoggingEvent`, not a separate `ThreadLocal` read by the encoder.
8. Do not add formatter/JSON work to disabled DEBUG/TRACE.
9. Preserve rolling-file, redaction, diagnostic exactly-once and severity→level contracts.
10. Explicitly describe wire-format migration and existing Elasticsearch indices.

<a id="решение-1--два-канала-с-разной-семантикой"></a>
## Decision 1 — two channels with different semantics

Split structured logging into **ambient context** and **event-local fields**:

```text
Envelope/driving metadata                    Operation result
run/source/stage/correlation                 duration/rows/counts/outcome data
             |                                            |
             v                                            v
        MdcScope                                     LogEvent
   Map<String, String>                         Map<LogField, Object>
             |                                            |
             |                              SLF4J 2 LoggingEventBuilder
             |                                  addKeyValue(key, value)
             +-----------------------+--------------------+
                                     v
                              ILoggingEvent
                         MDC + typed key/value pairs
                                     |
                                     v
                     Spring Boot ECS structured formatter
                                     |
                                     v
                           JSON strings/numbers/booleans
```

<a id="ambient-context--mdc"></a>
### Ambient context — MDC

MDC stores only textual data belonging to the entire execution scope:

- `ioc.run.id`;
- `ioc.source.id`;
- `ioc.source.path`;
- `ioc.source.content_hash`;
- `ioc.mode`;
- `ioc.stage`;
- control-event/correlation identifiers;
- transport endpoint/target identity when it describes the entire handler scope.

This allows third-party:

```java
logger.info("connection opened");
```

to receive run/source correlation without depending on project `LogEvent`.

<a id="event-local-structured-fields--slf4j-keyvalue-pairs"></a>
### Event-local structured fields — SLF4J key/value pairs

Durations, counts, revisions, boolean outcomes and other properties of one log
occurrence use the standard SLF4J 2 fluent API:

```java
logger.atInfo()
        .addKeyValue("event.action", "artifact_project")
        .addKeyValue("ioc.rows", 58L)
        .addKeyValue("event.duration", 18_324_056L)
        .log("artifact projection written");
```

`KeyValuePair.value` remains `Object` inside `ILoggingEvent`. Logback asynchronous
handoff therefore carries the type with the event snapshot and does not depend
on producer-thread state after the logger call returns.

MDC does not become a “legacy channel”: it remains the correct ambient
correlation tool, but stops being universal transport for all structured values.

<a id="решение-2--logfield-становится-исполнимой-схемой"></a>
## Decision 2 — `LogField` becomes an executable schema

Add a minimal closed vocabulary of JSON scalar types:

```java
public enum LogValueType {
    STRING,
    LONG,
    BOOLEAN
}
```

Do not add `DOUBLE`, arrays or arbitrary objects before the first real
producer/consumer contract. Current operational fields need three types;
a premature universal JSON type system would violate YAGNI.

`LogField` gains required `valueType`:

```java
EVENT_ACTION("event.action", STRING, "..."),
EVENT_DURATION("event.duration", LONG, "..."),
IOC_ROWS("ioc.rows", LONG, "..."),
IOC_SYNC_SHED_TO_RECONCILE(
        "ioc.sync.shed_to_reconcile", BOOLEAN, "...");
```

Type is as stable a downstream contract component as the key:

- Published `STRING → LONG` changes require index rollover/reindex.
- Renaming a key breaks saved queries.
- Add a field only with its first production producer.
- A structural test forbids missing types.

`LOGGING-CATALOG.md` gains a `JSON type` column:

```text
| Field | Namespace | JSON type | Description |
```

<a id="нормализация-значений"></a>
### Value normalization

`LogField`/a small package-private normalizer defines one contract:

- `STRING`: accepts `CharSequence`, enum, `Path`, UUID and other explicitly
  permitted textual values; the event receives their string representation.
- `LONG`: accepts only integral `Byte`, `Short`, `Integer`, `Long`; the event
  always receives `Long`.
- `BOOLEAN`: accepts only `Boolean`.
- `null`: field absent.
- Fractional numbers, arbitrary `Map/List` or incompatible types are programming
  defects, not reasons to silently stringify.

No per-type registry/Strategy: a closed three-way switch is simpler, cheaper and
more honest at this scale. A new JSON scalar type requires an explicit enum,
generator and test change.

<a id="удаление-string-escape-hatch"></a>
### Remove the string escape hatch

Remove public:

```java
LogEvent.field(String key, Object value)
```

All production fields go through `LogField`.

Add registered JDBC fields:

- `ioc.legacy_import.name`;
- `ioc.legacy_import.scanned`;
- `ioc.legacy_import.imported`;
- `ioc.legacy_import.skipped`;
- `ioc.legacy_import.failed`;
- `ioc.storage.sqlite.tuning`;
- `ioc.storage.sqlite.max_pool_size`;
- `ioc.storage.sqlite.write_max`;
- `ioc.storage.sqlite.read_max`;
- `ioc.storage.sqlite.busy_timeout_ms`.

The pre-implementation producer audit removes or wires constants lacking a
production producer. Confirmed at ADR time:

- `ioc.affected_rows`;
- `ioc.sync.running`;
- `ioc.sync.oldest_age`.

`ioc.sync.oldest_age` cannot remain a published number without units. If a
producer appears later, the key must explicitly establish units or use a
documented ECS/custom unit contract.

<a id="решение-3--logevent-остаётся-facade-transport-меняется-внутри"></a>
## Decision 3 — `LogEvent` remains a facade; transport changes internally

Production call sites retain the current fluent project API:

```java
LogEvents.info(log)
        .action(EventAction.ARTIFACT_PROJECT)
        .outcome(EventOutcome.SUCCESS)
        .field(LogField.IOC_ROWS, rows)
        .durationNanos(durationNanos)
        .message("artifact projection written")
        .log();
```

SLF4J fluent API does not spread across 20+ producer classes. `LogEvent` remains
a Facade/Builder with three responsibilities:

1. Validate and normalize values through `LogField`.
2. At an enabled level, build `LoggingEventBuilder` and add typed pairs.
3. Make one logger call with an optional cause.

It does not serialize JSON, know an ECS template or depend on Logback/Spring.

<a id="disabled-level-behavior"></a>
### Disabled-level behavior

Check `logger.isXEnabled()` before creating the backend builder or adding
key/value pairs. `LogEvent` still holds a small bounded `LinkedHashMap` of fields,
but a disabled event:

- creates no JSON;
- invokes no value suppliers/formatter;
- creates no framework-specific structured arguments/markers.

Per-item TRACE already has the external double gate from [0017], so typed
transport adds no work while TRACE is disabled.

<a id="ошибка-типа"></a>
### Type error

An incompatible value does not become a string again. It is a programming defect
to catch through unit/architecture tests and fail fast on a direct API call.
“Preserve the log at any cost” would recreate the schema corruption this ADR removes.

`DiagnosticSink` resilience stays unchanged: its outer decorator still isolates
observational-bridge failures. This ADR introduces no blanket swallowing of
SLF4J failures and does not hide programming errors.

<a id="решение-4--mdc-принимает-только-string-typed-fields"></a>
## Decision 4 — MDC accepts string-typed fields only

`MdcScope.put(LogField, Object)` checks:

```text
field.valueType == STRING
```

and rejects `LONG`/`BOOLEAN`. Remove public `put(String, Object)`; raw-key writes
remain a private scope implementation detail. Arbitrary keys cannot bypass taxonomy.

Add a narrow operation for the `LogEvent` merge protocol:

```java
MdcScope.hide(LogField field)
```

It temporarily removes an existing MDC key and restores it in `close()`, but
cannot write a new value or bypass field-type checks.

This makes the channel separation executable: a new numeric field cannot
accidentally return to string transport.

Migrate current numeric scope fields:

- `ioc.event.version` leaves ambient MDC and is added to specific
  `event_publish`/`event_dispatch` occurrences.
- `ioc.sync.files` leaves the entire remote-change handler scope and is emitted
  only by events where that count is actually calculated and meaningful.

Nested third-party logs retain event/run/correlation identity without accidental
event-local counters. This deliberately refines semantics rather than losing
observability.

<a id="решение-5--event-local-field-побеждает-одноимённый-mdc-key"></a>
## Decision 5 — an event-local field wins over the same MDC key

A key must not occur twice in JSON. A formatter's member order is not public
merge policy.

Contract:

```text
event-local field > ambient MDC field
```

Before the logger call, `LogEvent` temporarily removes only event-local-map keys
from MDC using `MdcScope.hide(...)`, then restores them through nested
`MdcScope.close()`. Consequently:

- `ILoggingEvent` receives one key;
- the typed event-local value does not degrade to an MDC string;
- outer MDC is restored after the call;
- with `AsyncAppender`, the existing snapshot contains correct MDC and key/value pairs;
- threads do not interfere because MDC is thread-local.

This protects boundary logs explicitly repeating `ioc.run.id` or another ambient
field. It does not invite every producer to duplicate context.

<a id="решение-6--spring-boot-34-built-in-ecs-formatter"></a>
## Decision 6 — Spring Boot 3.4 built-in ECS formatter

Upgrade the parent from Spring Boot `3.3.5` to **`3.4.13`** in a separate
behavior-neutral build commit. Do not jump two minor lines: 3.4 is the minimum
upgrade supplying the required standard transport and reduces migration risk.

After a green upgrade gate:

- remove `co.elastic.logging:logback-ecs-encoder`;
- replace `co.elastic.logging.logback.EcsEncoder` with
  `org.springframework.boot.logging.logback.StructuredLogEncoder`;
- select format `ecs`;
- preserve `RollingFileAppender`, filename, size/time rotation, `maxHistory`
  and `totalSizeCap`;
- keep console human-readable unless a separate decision makes it structured.

The Spring Boot formatter is selected because it:

- officially reads SLF4J fluent key/value pairs;
- preserves Object values in JSON;
- supports ECS without a custom serializer;
- belongs to the framework baseline already in use;
- has a documented JSON-customization seam.

The logging system initializes before `ApplicationContext`. The encoder is
therefore not a Spring bean, receives no application services and is not configured
through `AppConfig`. Its composition belongs to `logback-spring.xml` and early
`logging.structured.*` properties.

<a id="static-ecsservice-fields"></a>
### Static ECS/service fields

The new formatter must preserve:

- `service.name=ioc-extractor`;
- service version from one build/application property source;
- `event.dataset=ioc-extractor`;
- daemon file path;
- exception type/message/stack trace;
- logger/thread identity.

Set `event.dataset` through a Boot structured JSON static member/customizer,
not MDC. With one dataset this is an appender-level contract. Multiple datasets
need a separate decision removing the static member and sending dataset through
the event-local typed channel; two simultaneous sources for one key are forbidden.

<a id="решение-7--wire-format-migration-является-публичным-изменением"></a>
## Decision 7 — wire-format migration is a public change

Changing the encoder is not an internal refactor. At least these change:

- numeric/boolean values: JSON strings → JSON numbers/booleans;
- `ecs.version`: the Elastic encoder currently emits `1.2.0`; Boot 3.4 emits `8.11`;
- the formatter may add standard ECS members such as `process.pid`;
- JSON member order is not guaranteed;
- downstream parsers expecting strings only must accept JSON scalar types.

Stable contracts are:

- field names;
- JSON scalar type from `LogField`;
- action/outcome values;
- field units and semantics;
- explicitly documented presence rules.

Member order and whitespace are not contracts. Tests parse a JSON tree rather
than comparing an entire raw string.

<a id="решение-8--eventseverity-не-добавляется-механически"></a>
## Decision 8 — do not mechanically add `event.severity`

The typed channel can technically publish ECS `event.severity: long`, but
closing OBS-D3 must not simultaneously invent a new severity scale.

ECS `event.severity` is numeric severity **on the event source's scale** and need
not equal `log.level`. In the current project:

- diagnostics already publish exact enum severity in `ioc.diagnostic.severity`;
- `LoggingDiagnosticSink` maps it to `log.level`;
- generic operational events have no separate source severity.

Therefore this ADR does not publish `event.severity`. A dashboard request or
external severity contract would require a separate decision defining a stable
scale and scope. Typed transport alone does not justify adding a field.

<a id="решение-9--elasticsearch-template-остаётся-явным-activation-seam"></a>
## Decision 9 — Elasticsearch templates remain an explicit activation seam

Correct JSON types prevent new incorrect dynamic mappings but **do not repair
an existing index**:

- a field created as `keyword` does not become `long`;
- the first numeric documents after the producer fix encounter a mapping conflict;
- a new index/data-stream generation, rollover or reindex is required.

Elasticsearch is not currently a supported deployment consumer. OBS-D3 therefore
adds no unused installer/API client and does not pretend a template is installed.

The first supported Elasticsearch consumer activates registered seam **OBS-5**:

1. Generate a project component template from `LogField` metadata.
2. Map project `STRING` to `keyword`, `LONG` to `long`, `BOOLEAN` to `boolean`.
3. Validate ECS-owned fields against the selected ECS version/template instead
   of blindly duplicating them.
4. Numeric fields may gain `meta.unit`/`meta.metric_type` when their semantics
   are actually established.
5. Install the template **before** creating the first data stream/index.
6. Incompatible type changes require a versioned template + rollover.
7. Publish a reindex runbook for legacy indices; the producer does not attempt
   to “repair” mapping by resending.

Ownership:

- `platform-observability` owns schema metadata;
- the generator may live beside logging-catalog doc sync;
- Elasticsearch template/install/runbook belongs to `packaging`/a future ingest
  adapter, not core or `LogEvent`.

Record this seam now so taxonomy typing is not designed solely for the current
JSON file and does not lose the future mapping boundary.

<a id="границы-модулей-и-ответственности"></a>
## Module boundaries and responsibilities

| Module | Responsibility |
|---|---|
| `platform-observability` | `LogValueType`, typed `LogField`, `LogEvent` facade, string-only `MdcScope`, taxonomy/catalog tests |
| `platform-diagnostics-logging` | Diagnostic→`LogEvent` bridge; severity/redaction contract without encoder knowledge |
| adapters | Only select `LogField` and supply typed operational values |
| `bootstrap/ioc-app` | Spring Boot version, `logback-spring.xml`, early structured logging properties, real encoder integration tests |
| `docs/` | Wire contract, generated field catalog, migration consequences |
| future packaging/ES adapter | Component-template installation, rollover/reindex runbook |

`ioc-domain`, `ioc-application`, `platform-etl`, the diagnostics model and
control-event contracts gain no Spring/Logback imports. No business port is
introduced: SLF4J already forms the platform-observability boundary, and the
formatter is bootstrap infrastructure.

Create no custom `TypedEcsEncoder`, JSON serializer,
`ThreadLocal<Map<String,Object>>` or Spring logging-initialization bean.

<a id="concurrency-и-performance"></a>
## Concurrency and performance

<a id="concurrency"></a>
### Concurrency

- MDC stays thread-local and closes LIFO through `MdcScope`.
- Typed pairs belong to a specific `ILoggingEvent`.
- Async appenders receive frozen event data, not producer ThreadLocal state.
- Collision scopes restore only changed keys.
- Two synchronous runs on different daemon threads do not mix correlation.
- Automatic MDC propagation across new executor/reactive boundaries is not
  promised; introduce explicit context-propagation design when needed.

<a id="allocationruntime"></a>
### Allocation/runtime

One enabled event makes one pass over a small field map:

```text
O(number of event fields)
```

Usually 2–10 entries. There is no:

- JSON reparsing;
- regex/type guessing;
- global key-registry lookup per field;
- additional full log-message copy;
- hot-path reflection.

`Long` boxing during `addKeyValue` is unavoidable in Object-based SLF4J API and
negligible beside JSON formatting/I/O. Do not add special primitive collections
or code generation without a profiled bottleneck.

Create no unstable timing gate. Allocation/runtime smoke uses stage DEBUG events
and synthetic input with enabled per-item TRACE; optimize only measured regressions.

<a id="отклонённые-варианты"></a>
## Rejected alternatives

<a id="1-оставить-mdc-и-парсить-известные-keys-в-formatter-е"></a>
### 1. Keep MDC and parse known keys in the formatter

Schema-aware `String → Long/Boolean` conversion could fix JSON with few call-site
changes, but destroys the type and then reconstructs it. This:

- hides the transport defect;
- moves type errors into late encoder lifecycle;
- keeps ambient and event-local semantics mixed;
- permanently requires a custom formatter.

Allowed only as a temporary migration bridge, not target architecture.

<a id="2-парсить-любое-numeric-looking-mdc-value"></a>
### 2. Parse every numeric-looking MDC value

Rejected: run/event/slice identifiers may look numeric but are `keyword`.
The heuristic changes schema based on the first document's value.

<a id="3-наследоваться-от-elastic-ecsencoder"></a>
### 3. Extend Elastic `EcsEncoder`

Technically possible through `addCustomFields(...)`, but requires:

- a separate adapter/module or Logback/Elastic dependency in platform;
- custom scalar JSON escaping/serialization;
- collision policy with already serialized MDC;
- maintenance across encoder upgrades.

A fallback only if proven incompatibility blocks the Spring Boot upgrade.

<a id="4-перейти-на-logstash-logback-encoder"></a>
### 4. Move to `logstash-logback-encoder`

It supports typed structured arguments/key/value pairs but adds a dependency
family and requires manual ECS-shape maintenance. Built-in Boot ECS makes it
unnecessary infrastructure.

<a id="5-передавать-typed-map-через-отдельный-threadlocal"></a>
### 5. Pass a typed map through a separate ThreadLocal

Rejected: the encoder may run on another asynchronous thread, and hidden context
is not part of `ILoggingEvent`. It breaks when appender topology changes.

<a id="6-добавить-параллельные-keys-iocrows_long"></a>
### 6. Add parallel keys such as `ioc.rows_long`

Rejected: creates a permanent dual wire contract, complicates dashboards and
fails to protect new fields.

<a id="7-полностью-отказаться-от-mdc"></a>
### 7. Abandon MDC completely

Rejected: third-party framework logs lose run/source correlation, forcing every
adapter to repeat context manually in every occurrence.

<a id="8-переход-на-micrometer-observationopentelemetry-logs"></a>
### 8. Move to Micrometer Observation/OpenTelemetry logs

Metrics/tracing neither replace operational event logs nor resolve current
SLF4J→JSON typing themselves. A telemetry stack without a consumer/SLO violates YAGNI.

<a id="обязательные-инварианты-и-тесты"></a>
## Required invariants and tests

<a id="taxonomyapi"></a>
### Taxonomy/API

- Every `LogField` has a nonempty key/description and `LogValueType`.
- Keys are unique and obey namespace rules.
- `LOGGING-CATALOG.md` matches the generated catalog with its JSON-type column.
- No public arbitrary-key overload exists.
- A new structured key without `LogField` does not compile.
- A field without a production producer is removed or explicitly registered
  as a separate seam, not retained as a seed constant.

<a id="value-contract"></a>
### Value contract

- `LONG` accepts integral Java values and normalizes to `Long` before JSON.
- Fractional numbers for `LONG` are rejected.
- `BOOLEAN` accepts only boolean.
- Numeric-looking IDs remain strings.
- Null fields are absent, not serialized as `"null"`.
- Incompatible values get no silent `String.valueOf` fallback.

<a id="mdcevent-split"></a>
### MDC/event split

- Ordinary SLF4J logs inside a run scope receive string correlation fields.
- `MdcScope` rejects numeric/boolean `LogField`.
- `MdcScope.hide(...)` restores a hidden key without writing a new value.
- An event-local key wins over the same MDC key.
- The final JSON contains the key exactly once.
- Preexisting MDC is restored after logging and exceptions.
- Two parallel runs do not mix MDC.
- Async appenders retain typed pairs after producer-scope closure.

<a id="encoderwire"></a>
### Encoder/wire

Parse the real Boot `StructuredLogEncoder` as a JSON tree where:

- `event.duration` is an integral number;
- `ioc.rows` is an integral number;
- every diagnostic count is an integral number;
- `ioc.sync.shed_to_reconcile` is boolean;
- run/source/event IDs are strings;
- `service.name` and `event.dataset` are preserved;
- exception fields exist;
- `ecs.version` matches the Boot formatter contract;
- member order is not checked.

<a id="regression"></a>
### Regression

- OBS-1 severity→log.level mapping is unchanged.
- Diagnostic/TRACE redaction is unchanged.
- Disabled gates invoke no formatter/rendering.
- Diagnostic-sink logging-failure isolation is preserved.
- Daemon rolling file and human-readable oneshot console are preserved.
- New `logging.*` properties do not affect strict startup `ioc.*` preflight.

<a id="нарезка-реализации"></a>
## Implementation slices

<a id="1-spring-boot-34-baseline"></a>
### 1. Spring Boot 3.4 baseline

**Commit:** `BUILD: upgrade Spring Boot to 3.4.13`

- Update the parent baseline.
- Do not change encoder/LogEvent behavior in the same commit.
- Run dependency-convergence review, startup/config-binding tests, actuator,
  Spring Integration daemon tests and full reactor verify.
- Record only necessary migration adjustments.

**Gate:** `./mvnw verify`, oneshot help/startup, daemon context + actuator,
strict-configuration regression.

<a id="2-исполнимая-logging-schema"></a>
### 2. Executable logging schema

**Commit:** `REFACTOR: classify structured log field types`

- Add `LogValueType` and metadata to every `LogField`.
- Add JDBC fields; remove arbitrary string-field API.
- Remove/wire unused constants.
- Extend structural tests and generated catalog with a JSON-type column.
- Keep the current MDC transport for now, preserving behavior in this commit.

**Modules:** `platform-observability`, `adapter-store-jdbc`, `docs/`.

**Gate:** taxonomy/doc sync + affected adapter tests.

<a id="3-typed-event-transport-и-boot-ecs-formatter"></a>
### 3. Typed event transport and Boot ECS formatter

**Commit:** `FEATURE: emit typed ECS structured log fields`

- Move `LogEvent` to SLF4J `LoggingEventBuilder.addKeyValue`.
- Make `MdcScope` string-only and migrate numeric scope fields.
- Implement the collision contract.
- Replace Elastic encoder with Boot `StructuredLogEncoder`.
- Remove `logback-ecs-encoder` dependency/version property.
- Preserve rolling/service/dataset configuration.
- Rewrite log-capture tests from MDC event fields to key/value pairs.
- Add the real JSON-type matrix and async/concurrency regressions.
- Update `LOGGING.md`, taxonomy and module READMEs; close OBS-D3.

**Modules:** `platform-observability`, `platform-diagnostics-logging`, affected
adapters, `bootstrap`, `docs/`.

**Gate:** targeted tests, full `./mvnw verify`, `git diff --check`.

<a id="stand-verification--definition-of-done"></a>
## Stand verification / Definition of Done

After building the RC, deploy the daemon on a test stand:

1. A clean rolling file is created by the Boot ECS formatter.
2. Extraction with diagnostics emits numeric totals/counts.
3. Sync admission emits boolean `shed_to_reconcile`.
4. Stage completion emits numeric `event.duration`.
5. Python smoke checks `isinstance(value, int|bool)` and IDs as `str`.
6. Ordinary framework/adapter logs inside the run scope contain correlation MDC.
7. Parallel ingestion of two files does not mix run IDs.
8. Redaction and terminal completion remain unchanged.
9. Stand saved queries/scripts do not assume string counts.

If disposable Elasticsearch is available, an extra smoke creates mapping with
`coerce=false` and indexes a representative event. No Elasticsearch dependency,
container or timing gate is added to the ordinary reactor solely for this
optional stand check.

OBS-D3 is closed when:

- full verify and stand smoke are green;
- production JSON contains correct scalar types;
- the catalog publishes types;
- arbitrary field/MDC numeric escape paths are gone;
- `logback-ecs-encoder` is absent from the dependency tree;
- dev/module docs describe the dual-channel contract;
- OBS-5 is registered as a future Elasticsearch activation seam, without being
  presented as an implemented template.

<a id="следствия"></a>
## Consequences

Positive:

- Dashboards and aggregations receive numbers/booleans without runtime conversion.
- Field type has one source of truth.
- Correlation stays available to third-party logs.
- Logging backend is replaceable through the standard SLF4J event contract.
- New fields cannot silently bypass generated taxonomy.
- A future Elasticsearch template can be generated from the same metadata.

Negative/operational:

- A Spring Boot minor upgrade broadens the regression surface.
- ECS JSON wire format changes, including `ecs.version`.
- Existing Elasticsearch keyword mappings require rollover/reindex.
- Python/scripts expecting all custom values as strings need updating.
- Numeric event properties no longer spread through ambient MDC into every
  nested framework log.
- Type mismatch becomes a fail-fast programming defect.

<a id="открытые-точки-расширения"></a>
## Open extension points

1. **OBS-5:** generated Elasticsearch component template + installation/
   rollover/reindex lifecycle with the first supported Elasticsearch consumer.
2. `event.severity` only after agreeing a source-specific numeric scale.
3. `DOUBLE`/arrays/objects only with the first real field contract.
4. Async/reactive context propagation only when a real executor/reactive stage
   boundary appears.
5. Structured console output is a separate operator decision; OBS-D3 keeps
   human-readable oneshot console.

<a id="реализация-2026-07-16"></a>
## Implementation 2026-07-16

Implemented through this sequence:

1. `da9ff3f`: design accepted and published.
2. `a8867f9`: Spring Boot baseline upgraded to 3.4.13.
3. `e4e0ea5`: `LogField` became an executable scalar schema, JDBC escape keys
   registered, generated catalog gained JSON types.
4. `FEATURE: emit typed ECS structured log fields`: `LogEvent` moved to SLF4J
   key/value pairs, `MdcScope` restricted to strings, Elastic encoder removed,
   Boot ECS formatter and JSON/async/collision regressions enabled.

OBS-D3 is closed. OBS-5 remains a seam: the implementation fixes producer wire
types but deliberately does not install Elasticsearch templates or change
existing mappings.

<a id="источники"></a>
## Sources

- [Spring Boot 3.4 — Structured Logging](https://docs.spring.io/spring-boot/3.4/reference/features/logging.html#features.logging.structured)
- [SLF4J 2 fluent logging API](https://www.slf4j.org/manual.html#fluent)
- [Elastic ECS event fields](https://www.elastic.co/docs/reference/ecs/ecs-event)
- [Elasticsearch numeric field types](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/number)
- [Elasticsearch `coerce`](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/coerce)
- [Elasticsearch component templates](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-cluster-put-component-template)
- [Elastic `EcsEncoder` source](https://github.com/elastic/ecs-logging-java/blob/main/logback-ecs-encoder/src/main/java/co/elastic/logging/logback/EcsEncoder.java)
- [Elastic `EcsJsonSerializer` source](https://github.com/elastic/ecs-logging-java/blob/main/ecs-logging-core/src/main/java/co/elastic/logging/EcsJsonSerializer.java)
