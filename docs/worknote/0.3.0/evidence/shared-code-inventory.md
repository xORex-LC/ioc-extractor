---
title: "0.3.0 shared-code inventory"
version: "0.3.0"
goal_id: "R030-LIB"
status: "Pilot validated; publication pending"
document_type: "Evidence ledger"
source_of_truth: false
language: "en"
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
| `platform/platform-concurrency` | Cross-service platform candidate | Keyed asynchronous serialization and synchronous exclusion | application, adapter-ingest, bootstrap | Low: no project-owned dependency closure and no IOC types | Priority admission candidate `LIB-1` |
| `platform/platform-observability` | Mixed service-shared / candidate platform | Structured logging, MDC, value typing, sanitization, pipeline observer | diagnostics bridge, five adapters, bootstrap | Medium/high: IOC actions/fields and ETL observer dependency | Genericity split/admission required |
| `platform/platform-diagnostics-logging` | Service-shared bridge | Diagnostics-to-observability rendering/redaction/resilience | bootstrap | Inherits both diagnostics catalogs and observability vocabulary | Publish only after both API contracts are admitted |

## Publication candidates

| ID | Candidate | Category | Owner | Consumers | Dependency closure | State |
|---|---|---|---|---|---|---|
| `LIB-1` | `platform-concurrency` | Cross-service platform | Platform coordination | Current application/adapter-ingest/bootstrap; planned Java + Spring Boot `feeds-collector`, execution policy undecided | No runtime dependencies beyond JDK | `central-validated`; public release and repository consumers open |
| `LIB-2` | Generic diagnostics model/result/sink subset | Cross-service platform | Diagnostics | Current reactor consumers + planned `feeds-collector` | Must remove service exception/catalog coupling or explicitly version it | `genericity-review` |
| `LIB-3` | Generic observability/MDC/value typing subset | Cross-service platform | Observability | Current adapters/bootstrap + planned service | ETL observer and IOC action/field vocabulary must be excluded or separated | `genericity-review` |
| `LIB-4` | `platform-etl` | Cross-service platform | ETL | Current `ioc-application`; nearest consumer planned only | diagnostics + errors + their accepted publication closure | `deferred-second-consumer` |
| `LIB-5` | `platform-events` local control API | Cross-service platform, non-wire | Event coordination | Current application/bootstrap; nearest consumer planned only | None | `deferred-second-consumer` |

`LIB-1-HARDEN-01` implements bounded hardening of the existing module. Its
[execution record](../lib-1-concurrency-worknote.md) documents real consumers,
alternatives, proposed API and compatibility boundaries. ADR 0028 and the publication implementation now add the admitted API,
consumer-resolvable POM, sources/Javadoc and standalone local resolution.
Protected signing and Central staging validation now pass; public repository
qualification and future feeds business semantics remain open.
The owner has selected Maven Central plus GitHub Packages and reported the
`io.github.xorex-lc` namespace verified and the required secrets configured in
`LIBRARY PUBLISHING`; exact public setup and the unverified operational checks
are recorded in the execution record. This is setup evidence, not publication
evidence.

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
| `io.github.xorex-lc:ioc-platform-concurrency:0.3.0-rc.1` | Protected validation run `34222437897` passed; Central deployment `84d86a68-9402-4181-a587-772d004ad8c6` is `VALIDATED`, not published | Flattened POM signed and accepted in the validated bundle | Signed and accepted in the validated bundle | Signed and accepted in the validated bundle | Signed MD5/SHA-1/SHA-256 set retained with the immutable bundle | Local cold consumer passed in the protected build; Central and GitHub Packages cold consumers wait for publication |

## Rejected/deferred candidates

| Candidate | Disposition | Rationale | Owner | Revisit condition |
|---|---|---|---|---|
| `platform-errors` as standalone library | `defer` | Current API is the product-specific exception root, not a generic platform contract | Platform/error owner | A second service demonstrates identical exception semantics |
| Entire `platform-diagnostics` unchanged | `reject-as-is` | IOC code catalogs and service error root would leak into generic consumers | Diagnostics owner | Reconsider a deliberately smaller admitted API |
| Entire `platform-observability` unchanged | `reject-as-is` | IOC actions/fields and ETL observer broaden public API beyond generic logging mechanics | Observability owner | Reconsider a deliberately smaller admitted API |
| `platform-diagnostics-logging` first | `defer` | Bridge cannot have a smaller stable closure than both of its upstream contracts | Diagnostics/observability owners | Upstream publication units are admitted |
| Umbrella `commons`/`shared` JAR | `reject` | No cohesive owner/API/lifecycle and explicitly forbidden by goal contract | `R030-LIB` | New architecture decision with evidence, not duplication pressure |

### LIB-1 publication implementation (2026-09-07)

The pilot now uses `io.github.xorex-lc:ioc-platform-concurrency` in the reactor.
ADR 0028, a flattened consumer POM, sources/Javadoc, immutable signed-bundle
workflow and separate empty-cache consumer are implemented. Local file-repository
consumption is verified; no external coordinate is claimed published. Public
repository qualification remains open for both selected destinations.

### LIB-1 protected validation (2026-09-08)

Annotated component tag `ioc-platform-concurrency-v0.3.0-rc.1` fixes source
commit `c0f0f12f`. Protected run `34222437897` passed admission, full build,
owner-key signing and Central USER_MANAGED validation. Its immutable signed
bundle is retained for recovery; no public Central release or GitHub Packages
upload has occurred. Publication must resume from that run and deployment,
after which each repository still requires a separate empty-cache consumer.
