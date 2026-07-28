---
title: "0.3.0 shared-code inventory"
version: "0.3.0"
goal_id: "R030-LIB"
status: "Baseline intake ready"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-LIB — Shared-code inventory

Contract: [R030-LIB](../goals/R030-LIB-shared-libraries.md).

`BASE-INVENTORIES-09` performs screening, not admission. Existing module
boundaries are good evidence of ownership and dependency direction, but they do
not automatically define a stable external API. Direct consumers below are
reactor-local; the future `feeds-collector` is a confirmed nearest consumer,
not a current publication test.

## Existing modules

| Module/capability | Category | Responsibility | Current consumers | IOC-specific coupling | Publication disposition |
|---|---|---|---|---|---|
| `platform/platform-errors` | Service-shared | Root unchecked exception contract | diagnostics, ETL, application, four adapters, bootstrap | High: `IocExtractorException` and `com.iocextractor.common` express this product | Keep internal; not a first publication unit |
| `platform/platform-diagnostics` | Mixed service-shared / candidate platform | Diagnostic/result/policy/sink mechanics plus category catalogs | ETL, application, diagnostics bridge, four adapters, bootstrap | Medium/high: IOC capability code catalogs and `platform-errors` closure | Genericity split/admission required |
| `platform/platform-etl` | Candidate cross-service platform | Framework-free Pipes-and-Filters execution model | application, observability | Medium: diagnostics/error result semantics and only one business consumer | Defer until second consumer and closure review |
| `platform/platform-events` | Candidate cross-service platform, local only | In-process publish-only control-event mechanics | application, bootstrap | Low in API shape; current use remains service-local and explicitly non-wire | Defer publication; preserve anti-broker boundary |
| `platform/platform-concurrency` | Cross-service platform candidate | Bounded keyed serial/single-flight execution | bootstrap | Low: no project-owned dependency closure and no IOC types | Priority admission candidate `LIB-1` |
| `platform/platform-observability` | Mixed service-shared / candidate platform | Structured logging, MDC, value typing, sanitization, pipeline observer | diagnostics bridge, five adapters, bootstrap | Medium/high: IOC actions/fields and ETL observer dependency | Genericity split/admission required |
| `platform/platform-diagnostics-logging` | Service-shared bridge | Diagnostics-to-observability rendering/redaction/resilience | bootstrap | Inherits both diagnostics catalogs and observability vocabulary | Publish only after both API contracts are admitted |

## Publication candidates

| ID | Candidate | Category | Owner | Consumers | Dependency closure | State |
|---|---|---|---|---|---|---|
| `LIB-1` | `platform-concurrency` | Cross-service platform | Platform coordination | Current bootstrap + planned `feeds-collector` | No project-owned dependencies | `screening-ready` |
| `LIB-2` | Generic diagnostics model/result/sink subset | Cross-service platform | Diagnostics | Current reactor consumers + planned `feeds-collector` | Must remove service exception/catalog coupling or explicitly version it | `genericity-review` |
| `LIB-3` | Generic observability/MDC/value typing subset | Cross-service platform | Observability | Current adapters/bootstrap + planned service | ETL observer and IOC action/field vocabulary must be excluded or separated | `genericity-review` |
| `LIB-4` | `platform-etl` | Cross-service platform | ETL | Current `ioc-application`; nearest consumer planned only | diagnostics + errors + their accepted publication closure | `deferred-second-consumer` |
| `LIB-5` | `platform-events` local control API | Cross-service platform, non-wire | Event coordination | Current application/bootstrap; nearest consumer planned only | None | `deferred-second-consumer` |

`LIB-1` is a screening priority, not an admission decision. Before
implementation it still needs API minimization, ownership/versioning,
repository/credentials ADR, consumer-resolvable POM, sources/Javadoc and a
standalone consumer contract.

## Admission record template

### `LIB-<N>` — Capability

- **Category:**
- **Cohesive responsibility:**
- **Owner:**
- **Existing consumer:**
- **Confirmed nearest consumer:**
- **Shared semantics/reason to change:**
- **Why JDK/external library/local placement is insufficient:**
- **Proposed public API:**
- **Excluded service-specific concepts:**
- **Direct dependencies:**
- **Project-owned transitive closure:**
- **External dependency budget:**
- **Versioning/compatibility policy:**
- **Sources/Javadoc plan:**
- **Contract tests:**
- **Standalone consumer:**
- **Publication repository/credentials:**
- **Disposition:**

## Messaging boundaries

| Concern | Owning artifact | Business schemas allowed | Broker/framework allowed |
|---|---|---:|---:|
| Local control events | `platform-events` | No | No |
| Generic messaging API | Not present; future named messaging artifact after a real broker boundary exists | No | No |
| RabbitMQ adapter | Not present; future `adapter-messaging-rabbitmq` behind an inward port | No | Yes |
| Feeds integration contracts | Not present; future named `*-contracts` artifact owned by the service boundary | Yes | No |

## Publication evidence

| Artifact/version | Protected workflow | POM | Sources | Javadoc | Checksum | Standalone consumer |
|---|---|---|---|---|---|---|
| None | Not implemented | Current reactor POMs are not consumer-flattened | Not published | Not published | Not published | Not available |

## Rejected/deferred candidates

| Candidate | Disposition | Rationale | Owner | Revisit condition |
|---|---|---|---|---|
| `platform-errors` as standalone library | `defer` | Current API is the product-specific exception root, not a generic platform contract | Platform/error owner | A second service demonstrates identical exception semantics |
| Entire `platform-diagnostics` unchanged | `reject-as-is` | IOC code catalogs and service error root would leak into generic consumers | Diagnostics owner | Reconsider a deliberately smaller admitted API |
| Entire `platform-observability` unchanged | `reject-as-is` | IOC actions/fields and ETL observer broaden public API beyond generic logging mechanics | Observability owner | Reconsider a deliberately smaller admitted API |
| `platform-diagnostics-logging` first | `defer` | Bridge cannot have a smaller stable closure than both of its upstream contracts | Diagnostics/observability owners | Upstream publication units are admitted |
| Umbrella `commons`/`shared` JAR | `reject` | No cohesive owner/API/lifecycle and explicitly forbidden by goal contract | `R030-LIB` | New architecture decision with evidence, not duplication pressure |
