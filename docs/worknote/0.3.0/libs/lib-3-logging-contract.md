# LIB-3 L3-0: logging API and ECS contract

Status: **fixed implementation target**, 2026-10-19. The owner authorized L3-0
after the [ECS assessment](lib-3-logging-ecs-analysis.md). This closes contract
qualification. The L3-1 module now implements this target locally, with public
API tests and an executed consumer guide. L3-2 now delegates IOC mechanics and
implements the selected producer migrations; final integration qualification is
recorded in the plan.
IOC migration and publication admission remain separate stages in the
[execution plan](lib-3-logging-design.md#migration-and-publication-plan).
The [ADR draft](lib-3-logging-adr-draft.md) records the boundary and alternatives.

## Artifact, ownership and dependencies

| Item | Fixed target |
|---|---|
| Coordinates | `io.github.xorex-lc:ioc-platform-logging-slf4j:${revision}` |
| Reactor directory | `platform/platform-logging-slf4j` |
| Java baseline | 21; product/library versions remain lockstep |
| Packages | `com.iocextractor.platform.logging`, `.ecs`, `.redaction` |
| Maintainer/release owner | Repository owner `xORex-LC`; same publication ownership as the existing platform libraries |
| Required main dependency | `org.slf4j:slf4j-api`; resolved qualification baseline 2.0.18 |
| Initial actual provider fixture | Logback 1.5.38; Boot 4.0.8 for the application integration recipe |

The shared JAR contains no IOC catalogs, ETL/diagnostics/configuration dependency,
Spring or Logback main dependency, provider binding, starter, JSON encoder or
implicit execution-context propagation. It does not shade SLF4J. Provider choice,
messages, application actions, sensitivity policy and custom fields belong to
each service. The internal `platform-observability` module retains IOC adapters,
catalogs and the pipeline observer. Existing core dependency rules still apply:
this extraction does not introduce logging helpers into business modules.

The tested versions above are an exact fixture, not a claim about all SLF4J 2.x
providers or a security/dependency-update audit. Resolve the final consumer POM
and dependency closure again during packaging. Publication tooling must admit
SLF4J explicitly while retaining the existing libraries' zero-dependency guards.

## Public surface

Eight public types are admitted. Utility classes are final with no public
constructor; `LogEvent` is obtained through the factories. Normalization and
event-storage mechanics remain package-private.

| Package | Type | Public members |
|---|---|---|
| `logging` | `LogFieldSpec` | Record `(String key, LogValueType valueType)` and ordinary record value methods |
| `logging` | `LogValueType` | Enum `STRING`, `LONG`, `BOOLEAN` |
| `logging` | `MdcScope` | `static open()`, fluent `put(LogFieldSpec, String)`, fluent `hide(LogFieldSpec)`, `close()`; implements `AutoCloseable` |
| `logging` | `LogEvents` | Static `trace/debug/info/warn/error(Logger)`, each returning `LogEvent` |
| `logging` | `LogEvent` | Fluent `field(LogFieldSpec, Object)`, `action(String)`, `outcome(ecs.EventOutcome)`, `durationNanos(long)`, `message(String)`; `void log()`, `void log(Throwable)` |
| `logging.ecs` | `EcsFields` | Five `public static final LogFieldSpec` constants listed below |
| `logging.ecs` | `EventOutcome` | Enum `SUCCESS`, `FAILURE`, `UNKNOWN`; `String value()` |
| `logging.redaction` | `LogRedaction` | `static String fullMask()`, `static String urlComponents(String)` |

The `logging` abbreviation in this table means the full base package above.
The ECS convenience methods deliberately live on the same small facade; there
is no second builder, adapter SPI or separate ECS artifact. `action(String)`
accepts a service's action vocabulary instead of depending on IOC `EventAction`.

Null required arguments produce `NullPointerException`; invalid keys, scalar
representations, conflicting declarations and helper values produce
`IllegalArgumentException`. Writes after scope close produce
`IllegalStateException`. Provider/builder failures propagate. No retry, fallback
logger or OBS-6 containment is introduced. Error messages identify the field,
declared type and offending Java class where relevant, without including the
field value. Exact exception text is not a stable API.

### Descriptor and event identity

`key` and `valueType` must be non-null. A key consists of dot-separated segments
matching `[A-Za-z_][A-Za-z0-9_-]*`; empty segments, whitespace, leading/trailing
dots and other characters are rejected. Keys are case-sensitive, are not trimmed
or normalized, and must not include `@timestamp`. This is a structured field
path, not a general-purpose MDC string-key API. Service catalogs use a consistent
lower-case project namespace for custom fields.

Record equality includes key and type. **Event identity is the key alone**:

- Equivalent descriptors declare one field. The last successfully supplied value
  wins; replacement preserves the field's insertion position.
- Reusing a key with another declared type fails even if a previous null removed
  its emitted value. A first null declaration also establishes type identity.
- Normalize/check before committing a field update. A rejected call must leave
  both the prior value and type declaration intact.
- Null removes the emitted value. It does not hide ambient MDC or erase the
  declaration. Re-adding a removed value may use the normal new insertion position;
  consumers must not rely on JSON member order.

Do not use `Map<LogFieldSpec, Object>` as wire identity: the characterization
fixture demonstrates duplicate keys with conflicting types under that design.
Cross-event catalog uniqueness and parent/child paths such as `collector` versus
`collector.task.id` remain service schema checks. There is no global registry.

### Scalar representation and emission

| Declared type | Accepted non-null inputs | Normalized value |
|---|---|---|
| STRING | `CharSequence`, `Enum`, `Path`, `UUID` | Snapshot via `toString()` |
| LONG | `Byte`, `Short`, `Integer`, `Long` | `Long` |
| BOOLEAN | `Boolean` | `Boolean` |

Null omits any type. Numeric/boolean strings, floating-point values, arbitrary
objects, maps, arrays and collections are rejected. In particular, generic enum
conversion uses `toString()`, not `value()`; callers needing a wire vocabulary use
the helper or explicitly pass its wire string.

Field/helper validation happens during construction, including when the selected
level is disabled. At `log`, a disabled level skips provider emission and MDC
collision preparation. For an enabled level, each emitted field temporarily
hides the **exact same** MDC key, regardless of scalar type, and restoration is
attempted after delivery or failure. Open the collision scope in
try-with-resources **before** populating it. Follow each builder returned by
SLF4J `addKeyValue`/`setCause`; mutable-returning-self behavior is not assumed.

`message` requires a non-null string; its default is empty. `log(null)` is
equivalent to `log()`. The builder is mutable, owns one record's construction and
is not thread-safe. A second `log()` is another emission attempt. SLF4J/provider
acceptance does not establish durable delivery or successful JSON encoding.

### MDC scopes

`MdcScope` remembers each touched key's first previous string value and restores
touched keys in reverse order. Untouched MDC writes remain unchanged. `put`
requires a STRING descriptor; its String argument may be null to remove the key
for the scope. `hide` accepts any descriptor type and removes the ambient string.
Closing a successfully closed scope again is harmless.

Open, populate and close on the same thread, with nested scopes closed in LIFO
order. Other thread/order usage is outside the contract; v1 adds no scheduler,
implicit capture, global context or thread-order enforcement. Reconstruct worker
context from explicit command/event metadata inside the worker. Business state
must not rely on MDC. A provider that ignores MDC cannot supply restoration or
context-delivery behavior merely because SLF4J is present.

Acquire the resource before the first write:

```java
try (var scope = MdcScope.open()) {
    scope.put(TASK_ID, command.taskId());
    // Perform this operation on the current thread.
}
```

Fluent population in the resource initializer can fail before Java acquires the
scope. The new API does not make that usage safe automatically. Provider failures
during cleanup follow normal Java exception/suppression rules; successful complete
restoration is not promised if the provider itself fails.

The local IOC wrapper may retain `put(LogField, Object)` by explicitly converting
non-null values with `String.valueOf`. That convenience does not widen the new
shared String-only signature.

## Shared ECS profile

V1 shares this **scalar subset**, with meanings checked against ECS 8.11. The
actual Boot fixture emits `ecs.version=8.11`; the application encoder owns that
metadata. The library does not emit a competing version field or claim full ECS
conformance for arbitrary records.

| Constant | Key | Type | Meaning / helper |
|---|---|---|---|
| `EVENT_ACTION` | `event.action` | STRING | Service-defined action; `action(String)` rejects blank input without trimming it |
| `EVENT_OUTCOME` | `event.outcome` | STRING | `outcome(EventOutcome)` emits exactly `success`, `failure` or `unknown` |
| `EVENT_DURATION` | `event.duration` | LONG | Nanoseconds; `durationNanos(long)` accepts zero through `Long.MAX_VALUE`, rejects negatives |
| `ERROR_TYPE` | `error.type` | STRING | Exception/error type when no Throwable is supplied |
| `FILE_PATH` | `file.path` | STRING | Full file path including its name; service selects permitted disclosure |

Omit outcome when success/failure classification does not apply. Use `UNKNOWN`
only when the outcome of a relevant operation cannot be determined. A skipped
operation is not automatically an unknown or failed operation. Do not fabricate
zero duration when it was not measured; omit it. There is no implicit
millisecond conversion or `Duration` conversion. Callers own elapsed-time
measurement and overflow handling before passing nanoseconds.

`field(...)` validates representation only. It can bypass outcome/duration helper
semantics, as can direct SLF4J. Shared helpers plus service schema/producer tests
establish the supported profile; the facade is not a universal ECS validator.
`Path.toString()` preserves relative paths; the library does not resolve paths
against its working directory. The producer owns path meaning and permitted
disclosure. Sharing `FILE_PATH` does not certify all existing IOC path values:
review those producers before claiming service-wide profile conformance.
See the versioned [file](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/file.yml)
and [error](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/error.yml) definitions.

Optional `event.type` and `event.category` are **excluded** from v1. ECS models
them as arrays with classification vocabularies. Do not add unrestricted STRING
descriptors, collapse arrays to strings or introduce a collection type before a
real consumer and immutable-array contract are qualified. This does not prevent
services from using a separately tested raw SLF4J integration outside this subset.
See [versioned ECS event definitions](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/event.yml).

### Ownership and backend recipe

| Data | Owner / rule |
|---|---|
| Runtime JSON envelope | Encoder: `@timestamp`, `message`, `log.*`, `process.*`, `service.*`, `ecs.*` and other configured built-in metadata must not be redeclared by field catalogs/MDC |
| `event.dataset` | Application logging configuration; no ambient MDC or per-record override in the initial recipe |
| Error with Throwable | Encoder owns the entire `error.*` object; do not also supply explicit error fields through events or MDC |
| Error type without Throwable | Producer may supply `ERROR_TYPE`; no automatic exception object is fabricated |
| Common action/outcome/duration/path | Shared descriptor meaning; service decides when to include each value |
| Custom data | Service catalog under its project namespace; IOC uses `ioc.*`, another service may use `collector.*` |
| Trace identity | Real tracing integration; an IOC task/run ID does not become `trace.id` or `span.id` by renaming it |

The service checks its complete catalog and permitted MDC keys for duplicate
definitions, encoder-reserved paths and scalar/object prefix collisions. This is
a schema/test responsibility, not a claim that the generic library can intercept
arbitrary third-party MDC writes. Exact-key shadowing does not resolve nested
parent/child conflicts. Invalid output can be dropped by an appender without an
exception reaching the producer; qualification must inspect bytes and status.

Keep the IOC encoder and synchronous daemon RollingFileAppender wiring local for
now. The initial tested recipe needs mutable Logback `LoggingEvent` for dataset
injection, which restores original key/value pairs after encoding. No support is
claimed for `LoggingEventVO`, every async transport, arbitrary providers or a
plain `logging.structured.json.add.event.dataset` replacement: the latter fails
to merge with dynamic `event.*` in the probe. If a second real service needs
shared backend code, assess a dedicated Boot adapter then; do not distribute
unreviewed copies as a general backend guarantee.
See [Boot structured logging](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html).

## Explicit masking and URL transformation

`fullMask()` takes no input and returns exactly `[redacted]` at every logging
level. It neither evaluates nor retains the original value, its length, prefix
or fingerprint. The service decides whether an absent value should be omitted
or represented by the mask. A mask belongs to a STRING **log representation**;
LONG/BOOLEAN fields continue rejecting it. Migrating an existing numeric wire
field to text requires a service schema decision.

Null omission is not masking: ambient MDC can still supply the omitted key. Use
an explicit mask or a protected hide scope for that key, and avoid placing raw
secrets in MDC. Neither method scrubs copies in messages, Throwable, other fields
or unrelated ambient context. The IOC diagnostic fingerprint/severity policy
remains local and unchanged by extraction.

`urlComponents(String)` retains the existing **lexical** algorithm and its
`<redacted>` marker, distinct from the full mask. Null returns null; empty/plain
input is retained. When `://` exists, the authority ends at the first `/`, `?` or
`#`; any user-info through the last `@` in that authority becomes `<redacted>@`.
Then content after the first `?` through the next `#` or end becomes
`<redacted>`. No URI parsing, decoding or validity check is performed.

| Input | Result |
|---|---|
| `https://u:p@example.test/p?q=secret#f` | `https://<redacted>@example.test/p?<redacted>#f` |
| `u:p@example.test/p` | Unchanged: scheme-less user-info is not recognized |
| `example.test/p?q=secret` | `example.test/p?<redacted>` |
| `https://example.test/secret#secret` | Unchanged: path and fragment remain visible |
| `https://example.test/#f?q=secret` | `https://example.test/#f?<redacted>`: literal `?` is processed even inside a fragment |

The twelve probe examples pin delimiter ordering, repeated delimiters and partial
input. Changing this algorithm is a separate behavioral decision; do not describe
the existing helper as a complete URL/secret scrubber.

## IOC migration fixed for L3-2

These deliberate wire corrections accompany extraction. Keep logger categories,
actions, levels, messages and other fields unchanged. No invalid legacy field
is dual-written for compatibility.

| Producer / current behavior | Required target | Verification |
|---|---|---|
| `LoggingControlEventObserver`: internal event name in `event.type` | Use existing local `IOC_EVENT_TYPE` / `ioc.event.type` | Real observer-to-JSON fixture: custom value present, standard `event.type` absent |
| `LegacyLedgerImporter` and `SqliteUserVersionSchemaMigrator`: `event.type=skipped`, outcome `unknown` for informational no-op | New local STRING `IOC_OPERATION_DISPOSITION` / `ioc.operation.disposition=skipped`; omit both `event.type` and outcome on these two no-op records | Execute both producer branches; preserve their actions and other metadata, assert omission and new nested path |
| `LocalImportChangeSignalSource`: explicit `error.type` plus `.log(failure)` | Remove explicit type on the Throwable branch; retain the cause | Actual appender writes a complete JSON error record with exception metadata and no internal encoder failure |

Remove the local unrestricted `EVENT_TYPE` descriptor after its producers migrate.
Keep other type-only error producers supported. Regenerate the logging catalog
from its code source; do not hand-edit generated documentation. Document query
migration from `event.type` to `ioc.event.type` or `ioc.operation.disposition`,
including outcome omission on the two no-op paths, in the capability doc and
release notes. Accepted ADR-0018/0019 stay intact; promote the new ADR during
implementation qualification. OBS-9/10 remain open until these production changes
and their regression tests pass. OBS-6/7/8 and event-publication recovery semantics
are outside this extraction.

## Qualification and next gates

The [reproducible probe](l3-0-probe/README.md) passed **62/62** cases with zero
failures, aborts or skips on 2026-10-19, source HEAD
`662aa880587e11cea08204c250d89894abf1834c`. Its baseline pins all eight copied
source files and 23 third-party JARs by SHA-256. No IOC JAR or reactor output is
on the isolated classpath. The existing focused observer test also passed (one
case); `make doctor-core` passed.

This qualifies current generic mechanics, known failure paths and the selected
wire representations. It **does not** qualify implementations of the new public
record/helpers, full-mask method, publication or an external consumer. Cases
that reproduce defects pass because the observed defect is expected; they are
not evidence of remediation.

| Stage | Evidence still required |
|---|---|
| L3-1: complete locally | Actual public API tests: descriptor validation; first-null/repeated-null type identity; conflicting-type rejection and failure atomicity; exact helper vocabulary; duration `-1/0/MAX`; full mask; provider-failure cleanup; preserved copy-returning builder behavior |
| L3-2 | Real IOC producer migrations above, generated catalog checks and unaffected wire regressions; no duplicate emitters or independent copies in compatibility wrappers |
| L3-3 | English Javadoc for all public types/methods with concrete contracts, package docs, module README, executed consumer guide and qualified Boot recipe; module/architecture/analyzer ownership review; final `make verify` and `make pmd-analysis` |
| L3-4 | Parent-independent dependency-aware POM, sources/Javadoc, dependency allowlist and negative-resolution fixtures; cold standalone consumption and explicit publication admission |

Document API behavior alongside L3-1 implementation, rather than deferring all
documentation to L3-3. Later slices consolidate integration docs and final gates.
Examples describing the future API here are contract sketches, not claimed
compiled examples. The probe runs actual current algorithms only.

Historical L3-0 boundary: production Java, reactor and analyzer scope were unchanged. Existing full
verify/PMD evidence is stale at this HEAD; L3-0 does not refresh it. No live
Elasticsearch, future collector, external backend, load or published-consumer
qualification is inferred from L3-0. Current implementation evidence belongs to
the L3-1 execution record in the plan.
