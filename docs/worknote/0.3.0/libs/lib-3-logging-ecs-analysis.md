# LIB-3: reusable logging and ECS profile analysis

Status: pre-L3-0 analysis and proposed boundary, 2026-10-19. The subsequent
[fixed contract](lib-3-logging-contract.md) closes its open design choices.
No extraction, production
correction or publication admission is claimed. This refines the
[LIB-3 proposal](lib-3-logging-design.md) following the owner's request for a
consistent logging approach across future services.

## Result

Extract the logging mechanics and a small, explicit ECS profile in one SLF4J API
library. Keep IOC actions, custom fields, observers and rendering policy local.
Keep Spring Boot/Logback serialization outside the shared JAR and qualify it
through a reusable configuration recipe and actual-output contract tests.

The current implementation is useful extraction material, but copying its
catalog would also copy incorrect `event.type` semantics and an encoder collision
that can discard an error record. Correct these deliberately during IOC migration;
they are not compatibility behavior to reproduce in new services.

## Baseline and method

- Branch: `r030-libraries`; HEAD:
  `662aa880587e11cea08204c250d89894abf1834c`.
- Java 21.0.11, Spring Boot 4.0.8, SLF4J 2.0.18, Logback 1.5.38, resolved from
  the current build. No dependency upgrade was made.
- The pre-existing `KNOWN-ISSUES.md` change for TOOL-6 was preserved.
- Reviewed the observability module, its ETL dependency, diagnostic bridge,
  bootstrap encoder/configuration, standard-field producers, tests and generated
  catalog inputs. Read ADRs [0018](../../../ADR/0018-typed-ecs-structured-logging.md)
  and [0019](../../../ADR/0019-spring-boot-4-nested-ecs.md); accepted ADRs remain
  unchanged.
- Compared semantics against the versioned
  [ECS 8.11 event schema](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/event.yml).
  Boot 4.0.8 emits `ecs.version=8.11`; a moving ECS reference is not a reason to
  advertise a newer schema version.
- Characterized actual compiled code with synthetic inputs and the resolved
  application classpath. Raw records were checked for duplicate member names;
  direct encoding and Logback appender behavior were checked separately.

## Findings

| ID / priority | Evidence | Impact and disposition |
|---|---|---|
| ECS-1 / high | `LogField.EVENT_TYPE` is STRING. `LoggingControlEventObserver` supplies internal event names; `LegacyLedgerImporter` and `SqliteUserVersionSchemaMigrator` supply `skipped`. | These are not allowed ECS classifications. Move internal names to existing `ioc.event.type`; choose a service field or omission for skipped state. Do not blindly preserve these values in a common profile. Tracked as OBS-9. |
| ECS-2 / high | `LocalImportChangeSignalSource` combines explicit `error.type` with `.log(failure)`. Boot separately writes the Throwable's `error` object. | Direct encoding throws; a real OutputStreamAppender returns from the log call but writes zero bytes and records an internal error. The matching production branch can lose its JSON failure record. Tracked as OBS-10; no production incident is established. |
| ECS-3 / medium | The facade has only STRING/LONG/BOOLEAN; a list for `event.type` throws. A raw SLF4J list is serialized as an array by the same encoder. | The limitation is in the current field API, not the backend. Supporting `event.type` or `event.category` needs an explicit immutable string-list contract; scalar-only v1 must leave them outside its supported profile. |
| ECS-4 / medium | The dataset adapter checks event key/value pairs only. An ambient `event.dataset` conflicts with its injected pair; a `service.name` context pair conflicts with Boot metadata. | Define field ownership across configuration, context and event data. Exact-key MDC hiding alone does not protect fields written in a separate formatter section. Synthetic cases fail encoding. |
| ECS-5 / medium | Context `event` plus event-local `event.action`, and context `collector.task.id` plus event-local `collector`, both fail nested rendering. | Catalog checks must reject scalar/object path conflicts. Per-event same-key/type identity is necessary but insufficient for nested JSON. |
| ECS-6 / medium | The custom encoder enriches mutable `LoggingEvent` only. `LoggingEventVO` is encoded without dataset. | Current daemon configuration uses a synchronous rolling appender; no current daemon dataset loss is established. Do not advertise arbitrary appender/remote-event compatibility from this implementation. |
| ECS-7 / contract gap | Raw `.field(EVENT_OUTCOME, "skipped")` and `.durationNanos(-1)` are accepted by the facade. | Representation checks do not validate ECS meaning. Qualify vocabulary and duration semantics through the selected profile; no invalid duration producer was established by these probes. |
| ECS-8 / coverage gap | The existing exact JSON fixture is one successful app-start record; the control-event observer test checks levels/version. | Both pass while ECS-1/ECS-2 remain. Add representative control-event and error-output cases during implementation. Passing JSON syntax does not establish ECS semantics. |

Source locations:

- [Field catalog](../../../../platform/platform-observability/src/main/java/com/iocextractor/observability/LogField.java),
  [event facade](../../../../platform/platform-observability/src/main/java/com/iocextractor/observability/logging/LogEvent.java),
  [normalizer](../../../../platform/platform-logging-slf4j/src/main/java/com/iocextractor/platform/logging/LogValueNormalizer.java).
- [Control-event observer](../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/LoggingControlEventObserver.java),
  [legacy importer](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/LegacyLedgerImporter.java),
  [schema migrator](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/SqliteUserVersionSchemaMigrator.java).
- [Change-signal failure producer](../../../../adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/in/ingest/LocalImportChangeSignalSource.java),
  [encoder](../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocEcsStructuredLogEncoder.java),
  [Logback wiring](../../../../bootstrap/ioc-app/src/main/resources/logback-spring.xml).
- [Exact-output test](../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LogbackConfigurationIT.java),
  [observer test](../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/LoggingControlEventObserverTest.java).

### What the standard requires

`event.action` is implementer-defined. `event.outcome` has the shared vocabulary
success/failure/unknown and may be absent. An informational event with no meaningful
outcome should not receive unknown merely to fill a field. `event.duration` uses
nanoseconds. `event.type` and `event.category` are array classifications with
defined values, not service event names. These rules already exist in the
[ECS 8.11 event schema](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/event.yml).

An Elasticsearch keyword mapping may accept a scalar or an arbitrary string;
that does not make its semantics or producer shape correct. No live Elasticsearch
consumer was tested here. Mapping generation/installation remains OBS-5.

Boot contributes context pairs and Throwable fields in separate formatter
sections. It supplies the JSON representation, not validation of service values
against the ECS categorization vocabulary.
[Boot 4.0.8 formatter source](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.0.8/core/spring-boot/src/main/java/org/springframework/boot/logging/logback/ElasticCommonSchemaStructuredLogFormatter.java).

## Proposed ownership

The live field catalog has **108 fields: six standard paths and 102 `ioc.*`
paths**. Its representation types are 56 STRING, 50 LONG and two BOOLEAN.
It is a service catalog with a small standard subset, not a general ECS model.

| Concern | Shared library/profile | Service or integration owner |
|---|---|---|
| Field definitions | Immutable key/type descriptors; published standard descriptors for the supported subset | Custom descriptors, descriptions, units, cardinality and sensitivity |
| `event.action` | Standard STRING descriptor and usage contract | Values such as `stage_complete`, `sync_fetch` or a collector operation |
| `event.outcome` | Shared enum and exact wire values; omission remains valid | Whether a record has an outcome and whose outcome it describes |
| `event.duration` | LONG nanoseconds; profile duration helper rejects negative values and defines overflow handling | What interval is measured; use a suitable elapsed-time source |
| `file.path` | Standard STRING descriptor | Which path is useful and safe to disclose |
| `error.*` | Documented ownership rule and output fixture | With a Throwable, let the encoder write its error object. A type-only record can use `error.type` without a Throwable; do not mix both routes |
| `event.dataset` | Integration contract and fixture | Static dataset configuration; dynamic override, if supported, needs an explicit rule |
| `@timestamp`, `ecs.version`, `log.*`, `process.*`, `service.*` | Documented reserved ownership in the initial Boot recipe | Encoder/runtime/configuration; event catalogs must not redefine them |
| `event.type`, `event.category` | Optional supported extension only after array API and vocabulary are qualified | Explicit semantic classification of each operation; never derive it from a class/event name |
| Correlation | MDC restoration and explicit context reconstruction rules | Run/delivery IDs remain custom; real tracing IDs come from the service's tracing integration |
| Masking | Explicit whole-value mask and narrowly documented URL helper | Selection of sensitive fields, message/cause policy and retention |

Do not rename `ioc.run.id` or control-event correlation IDs to `trace.id` merely
to share their spelling. ECS tracing describes distributed trace/span identity;
it is not a generic operation-ID bucket.
[ECS 8.11 tracing schema](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/tracing.yml).

Retain `ioc.*` locally. A future service uses its own stable project namespace
for custom fields and does not add custom meanings under standard ECS paths.
Project namespaces reduce future field collisions but cannot eliminate them.
[Elastic custom-field guidance](https://www.elastic.co/docs/reference/ecs/ecs-custom-fields-in-ecs).

## Artifact and API recommendation

Use the proposed `ioc-platform-logging-slf4j` artifact, Java 21 and the existing
lockstep release policy. Put the supported ECS descriptors/outcome vocabulary
in `com.iocextractor.platform.logging.ecs` within that JAR. These values need only
JDK types; emission/context mechanics depend on SLF4J API. Do not add Spring,
Logback, ETL or IOC dependencies to the published main dependency closure.

Do not extract the entire current `LogField` or `EventAction` enums. IOC may keep
thin compatibility wrappers delegating to the shared implementation; its standard
descriptors should reference shared definitions rather than fork their metadata.
The existing `EventOutcome` can delegate to the shared vocabulary while preserving
its public IOC conveniences during migration.

The generic facade validates representation. Profile helpers and service catalog
checks validate the supported standard meanings and reserved ownership. Direct
SLF4J calls, raw descriptors and third-party MDC can bypass those conventions;
the library cannot promise that every log emitted by a service conforms to ECS.
This separation must be explicit in the public documentation and fixtures.

For the first profile, prefer the five valid current scalar paths (`event.action`,
`event.outcome`, `event.duration`, `error.type`, `file.path`) plus integration rules.
The erroneous current `event.type` is not a sixth reusable scalar descriptor.
L3-0 must choose between omitting optional type/category from v1 and introducing
immutable string-list support with their allowed vocabulary. The small scalar
profile is the default recommendation; broader categorization needs a concrete
consumer requirement. Do not silently add arbitrary objects, JSON maps or a full
upstream ECS catalog to solve this one limitation.

Keep the Boot encoder recipe and tests in the application initially. A second
Boot consumer must be able to reproduce the documented configuration and JSON
contract. If it needs the same custom encoder code, evaluate a separate Boot
integration artifact at that point; repeated copying is not the long-term reuse
mechanism. The main library remains usable without that adapter.
Before adding such an artifact, qualify Boot's documented JSON-customizer seam
against the same collision fixtures. Its existence alone does not prove that
static additions and dynamic nested fields merge safely.
[Boot customization reference](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html#features.logging.structured.json).

Alternatives considered:

- **Generic tools only:** smallest contract, but leaves each service to repeat ECS
  semantics and the same mistakes; insufficient for the new requirement.
- **Entire ECS model or Boot starter in the main JAR:** much larger compatibility
  and framework surface without a demonstrated need; reject for the initial cut.
- **Separate ECS-only JAR now:** dependency purity is possible, but adds another
  publication/API boundary without an independent ECS-model consumer; defer.

## Migration and acceptance

L3-0 is the next step, not complete from this analysis. It must settle:

1. Artifact/package names, supported ECS subset/version, public descriptors and
   outcome helpers, duration validation and the optional array decision.
2. Key/type identity, protected encoder-owned paths, custom namespace policy and
   scalar/object conflicts across the service catalog and ambient context.
3. Error-field ownership, dataset behavior and failure reporting for the actual
   supported Boot/Logback combination.
4. Deliberate migration of the three `event.type` producers and the error collision.
   Record changed keys/shapes and example consumer queries; preserve unaffected
   fields, actions and logger categories. A rename or STRING-to-array change is a
   wire change even when it corrects a standard violation. Accepted ADR changes
   require a new decision rather than editing ADR-0018/0019.
5. Existing scope/masking contracts from the design proposal, including partial
   scope setup cleanup. Preserve the owner's OBS-6 throwing decision; fixing the
   renderer collision does not authorize a general logging-containment redesign.

Implementation needs public Javadoc, package docs, module README and an English
consumer guide with executed examples. Include a second, non-IOC field catalog,
same-thread nested scopes, masking limits, ownership rules and a Boot integration
example. No automatic context propagation, secret scrubber or log backend is implied.

The actual-output suite should cover successful and failed operations, internal
control events, type-only errors, Throwable errors, same-key MDC collisions,
reserved paths, parent/child conflicts, duration boundaries and dataset behavior.
Use strict duplicate-member detection as well as JSON type/path assertions. An
async fixture needs bounded coordination, containing timeout and guaranteed cleanup.

Publication tooling now admits concurrency and diagnostics, both JDK-only. Its
consumer-POM validation rejects all dependencies. SLF4J-bearing publication needs
an explicit dependency allowlist and isolated consumer-resolution qualification;
keep the existing two components' zero-dependency guards. API compatibility
automation remains the separately recorded TOOL-6 follow-up.

## L3-0 follow-up — 2026-10-19

The owner subsequently authorized contract qualification. The
[fixed L3-0 contract](lib-3-logging-contract.md) selects the five-field scalar
subset, array deferral, exact API/ownership and explicit OBS-9/10 migrations.
Its [versioned 62-case probe](l3-0-probe/README.md) extends the exploratory cases
below with unrelated descriptors, scope/masking/URL boundaries and reproducible
source/dependency identities. This does not change the dated findings or close
their production defects; L3-1/L3-2 implementation remains pending.

## Executed evidence and limits

| Check | Result |
|---|---|
| `make doctor-core` | Passed; suitable Java/build tools available |
| `make test-module MODULE=platform/platform-observability` | Six local suites, 23 cases; no failures/errors/skips |
| `make test-one MODULE=bootstrap/ioc-app TEST=LoggingControlEventObserverTest` | One case; no failures/errors/skips |
| `make test-one MODULE=bootstrap/ioc-app TEST=LogbackConfigurationIT` | Two cases; no failures/errors/skips |
| Synthetic characterization | 17 expected baseline observations confirmed; successful JSON records have unique member names |

The 17 cases were: baseline typed record; internal event type; skipped type; raw
type array; facade array rejection; raw invalid outcome acceptance; negative
duration acceptance; dataset in MDC; dataset in event data; service metadata
collision; error type plus cause; cause only; error type only; parent then child;
child then parent; immutable event; actual appender error type plus cause.
Synthetic rejection cases confirm existing limits, not compliance with the
proposed contract. Probe source, classpath, JSON records and command logs were
retained locally in ignored `.dev/lib3-ecs-analysis/`; they are exploratory
evidence, not a committed regression suite or publication gate.

Full `make verify` and `make pmd-analysis` were not run for these documentation
changes. The focused Failsafe command runs upstream verification with an explicit
test selector; it is not a complete reactor gate. Recorded full-gate freshness
remains false for this HEAD. No new shared API, independent consumer, live
Elasticsearch integration or production deployment has been qualified.
