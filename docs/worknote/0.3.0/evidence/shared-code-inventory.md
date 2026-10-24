---
title: "0.3.0 shared-code inventory"
version: "0.3.0"
goal_id: "R030-LIB"
status: "LIB-1 through LIB-3 qualified; configuration published, CI recovery pending"
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
| `adapters/adapter-config-spring-boot` | Published cross-service integration | Record-schema name inspection and ordered source observations | IOC bootstrap, unrelated-schema Binder tests and executable restricted-context guide | Spring Boot/Core with transitive Spring Context closure; schemas, source loading and enforcement remain service-owned | `0.3.0-rc.1` published to Central/GitHub; GitHub CI consumer and local Central recovery passed; tooling integration/CI recovery pending; [release record](../libs/configuration-library-design.md#protected-publication-record--2026-10-24) |
| `platform/platform-errors` | Service-shared | Root unchecked exception contract | diagnostics, ETL, application, four adapters, bootstrap | High: `IocExtractorException` and `com.iocextractor.common` express this product | Keep internal; not a first publication unit |
| `platform/platform-diagnostics-api` | Cross-service platform | JDK-only values, results/policies, bounded samples/counts and sinks | Local diagnostics, ETL, application, adapters and bootstrap | JDK-only closure; isolated local and remote RC consumers qualified | `published-qualified`; `0.3.0-rc.1` from Central and GitHub Packages |
| `platform/platform-diagnostics` | Service-shared product metadata | IOC catalogs, exception and rendering | ETL, application, diagnostics bridge, four adapters, bootstrap | IOC categories/messages and `platform-errors` closure | Retained locally after D1 split |
| `platform/platform-etl` | Candidate cross-service platform | Framework-free Pipes-and-Filters execution model | application, observability | Medium: diagnostics/error result semantics and only one business consumer | Defer until second consumer and closure review |
| `platform/platform-events` | Cross-service platform extraction in progress | In-process publish-only control-event mechanics | application, bootstrap | Low in API shape; current use remains service-local and explicitly non-wire | Owner resumed implementation on 2026-10-24; E1 JDK qualification, then separate Spring adapter; publication admission pending |
| `platform/platform-concurrency` | Cross-service platform candidate | Keyed asynchronous serialization and synchronous exclusion | application, adapter-ingest, bootstrap | Low: no project-owned dependency closure and no IOC types | Priority admission candidate `LIB-1` |
| `platform/platform-logging-slf4j` | Published cross-service platform | Typed SLF4J records, restoring MDC, scalar ECS profile and explicit masks | IOC observability plus isolated plain/Boot consumers; planned collectors | Required SLF4J API 2.0.18 only; providers/IOC/ETL remain outside | `0.3.0-rc.1` published and cold-consumed from Central and GitHub Packages |
| `platform/platform-observability` | Service-shared product integration | IOC field/action catalogs, shared logging facades and pipeline observer | diagnostics bridge, five adapters, bootstrap | IOC vocabulary and ETL observer remain service-owned | Retained locally after LIB-3 extraction |
| `platform/platform-diagnostics-logging` | Service-shared bridge | Diagnostics-to-observability rendering/redaction/resilience | bootstrap | Inherits both diagnostics catalogs and observability vocabulary | Publish only after both API contracts are admitted |

## Publication candidates

| ID | Candidate | Category | Owner | Consumers | Dependency closure | State |
|---|---|---|---|---|---|---|
| `LIB-1` | `platform-concurrency` | Cross-service platform | Platform coordination | Current application/adapter-ingest/bootstrap; planned Java + Spring Boot `feeds-collector`, execution policy undecided | No runtime dependencies beyond JDK | `published-qualified`; Central and GitHub Packages cold consumers passed |
| `LIB-2` | Generic diagnostics model/result/sink subset | Cross-service platform | Misyurkeev Denis (`xORex-LC`) / Diagnostics | Current reactor consumers + planned `feeds-collector` | JDK-only shared module; IOC catalogs, exceptions and logging remain local | `design-admitted`; D0–D4 locally qualified with ADR 0044; D5 remote publication/consumers pending |
| `LIB-3` | `platform-logging-slf4j` | Cross-service platform | Misyurkeev Denis (`xORex-LC`) / Observability | IOC facades, independent plain/Boot consumers; planned collectors | Required SLF4J API only; no provider/IOC/ETL dependency | `published-qualified`; `0.3.0-rc.1`, both repositories and negative dependency checks passed |
| `LIB-4` | `platform-etl` | Cross-service platform | ETL | Current `ioc-application`; nearest consumer planned only | diagnostics + errors + their accepted publication closure | `deferred-second-consumer` |
| `LIB-5` | `platform-events` local control API + separate Spring bridge | Cross-service platform, non-wire | Event coordination | Current application/bootstrap; future services selected by owner, not deployed evidence | JDK core; Spring bridge requires core + Spring Context + SLF4J API | `implementation-in-progress`; owner resumed on 2026-10-24; E1 has 43 focused tests, strict Javadoc and local example evidence; E2–E5 pending |

`LIB-1-HARDEN-01` implements bounded hardening of the existing module. Its
[execution record](../lib-1-concurrency-worknote.md) documents real consumers,
alternatives, proposed API and compatibility boundaries. ADR 0028 and the publication implementation now add the admitted API,
consumer-resolvable POM, sources/Javadoc and standalone local resolution.
Protected signing, immutable recovery publication and separate Central/GitHub
Packages consumers now pass. Future feeds business semantics remain open and
are not inferred from the publication consumer.
The owner has selected Maven Central plus GitHub Packages and reported the
`io.github.xorex-lc` namespace verified and the required secrets configured in
`LIBRARY PUBLISHING`; the protected runs subsequently verified the credentials,
signing identity and both repository paths. Exact evidence is recorded in the
execution record.

### LIB-2 D0 design admission — 2026-10-18

The owner requested fixing the diagnostics contract. Its
[D0 record](../libs/lib-2-diagnostics-design.md) selects
`io.github.xorex-lc:ioc-platform-diagnostics-api`, an 18-type JDK 21 surface,
named maintainer `xORex-LC`, zero non-JDK production dependencies and lockstep
versions. All 38 existing diagnostics types are disposed: 17 extracted after
adaptation, 21 retained locally, plus the adapted resilient sink from the logging
bridge. The [ADR draft](../libs/lib-2-diagnostics-adr-draft.md) records rationale
for D4 promotion.

`design-admitted` means the implementation target is fixed. It does not claim
the dependency seams have been removed, public signatures compiled, migration
tests passed or a standalone artifact qualified. D1–D5 retain those checks;
actual publication remains separately authorized. The existing focused 123-test
result covers unchanged code only. No LIB-2 release row is added to publication
evidence.

### LIB-2 D1 implementation — 2026-10-18

The [D1 record](../libs/lib-2-diagnostics-design.md#d1-implementation-and-qualification-record--2026-10-18)
tracks the coherent shared-model migration, code/API/package documentation and
consumer guide with executable examples. IOC catalogs, exception and logging
metadata remain local. The reactor's new API coordinate has no non-JDK runtime
dependency. Existing quality floors and module ratchets are preserved; the
new module has its own measured ratchet. D2 public-boundary qualification and
D3 standalone packaging remain separate from publication admission.

### LIB-2 D2 boundary qualification — 2026-10-18

The [D2 record](../libs/lib-2-diagnostics-design.md#d2-public-boundary-qualification--2026-10-18)
extends the API suite from 49 to 77 cases and qualifies IOC pipeline/checkpoint
behavior, exact counts and delivery, partial failures and documented boundaries.
One runner defect is fixed: terminal delivery cannot replace the original failure
by attempting to suppress that same exception instance. Full offline verification,
PMD policy/watchlist and documentation checks pass without changing existing
floors, scopes, accepted findings or dependency closure. Independent packaging
and publication evidence remain pending; this does not promote LIB-2 to
`published-qualified`.

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
| `io.github.xorex-lc:ioc-platform-concurrency:0.3.0-rc.1` | Validation run `34222437897`; recovery publication and qualification run [`34233605995`](https://github.com/xORex-LC/ioc-extractor/actions/runs/34233605995) passed against the same signed bundle and Central deployment `84d86a68-9402-4181-a587-772d004ad8c6` | Published and resolved from Central and GitHub Packages | Published and resolved from Central and GitHub Packages | Published and resolved from Central and GitHub Packages | All 20 signed/checksummed repository files matched the retained manifest; Central comparison reported 20/20 | Separate empty-cache Central and GitHub Packages consumers compiled and executed successfully; logs retained as artifact `10058905999` |
| `io.github.xorex-lc:ioc-platform-diagnostics-api:0.3.0-rc.1` | Validation run `39133285503`; publication and qualification run [`39137777538`](https://github.com/xORex-LC/ioc-extractor/actions/runs/39137777538) reused the original signed bundle and deployment `a48b06c1-c6e4-4a7b-b9b3-f9d1806be842` | Published and resolved from both repositories | Published and resolved from both repositories | Published and resolved from both repositories | All 20 repository files checked; four primary artifacts matched exactly in each consumer | Separate empty-cache consumers compiled and executed all three guide examples; logs retained as artifact `12140822161` |

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
repository qualification remained open at this implementation checkpoint.

### LIB-1 protected validation (2026-09-08)

Annotated component tag `ioc-platform-concurrency-v0.3.0-rc.1` fixes source
commit `c0f0f12f`. Protected run `34222437897` passed admission, full build,
owner-key signing and Central USER_MANAGED validation. Its immutable signed
bundle is retained for recovery; no public Central release or GitHub Packages
upload had occurred at this validation checkpoint. Publication therefore had to
resume from that run and deployment before each repository's separate
empty-cache consumer could execute.

### LIB-1 protected publication and qualification (2026-09-08)

The first publication run `34229094758` released the validated deployment to
Central, then failed on an HTTP 302 while verifying GitHub Packages. Recovery
fixes `fa5e5a8f`, `26e58cef` and `46510880` added constrained read redirects,
made qualification run after a skipped recovery build, preserved Central
deployment evidence and retained consumer logs. Their complete CI runs
`34231003678`, `34232060476` and `34233119093` passed.

Final protected recovery run
[`34233605995`](https://github.com/xORex-LC/ioc-extractor/actions/runs/34233605995)
used the original signed artifact rather than rebuilding it. Central and GitHub
Packages both matched the exact bundle, then isolated empty-cache consumers
resolved, compiled and ran against each repository. Retained artifacts are
`signed-library` `10058869990`, `central-deployment` `10058880886` and
`repository-consumers` `10058905999`; all expire on 2026-12-07. The published
unit therefore satisfies the `R030-LIB` definition of done and closes
`TEST-PUBLICATION-08`. Snapshot policy and real `feeds-collector` execution
semantics remain separate future decisions.


### LIB-2 D3 packaging qualification — 2026-10-18

The diagnostics component has a flattened zero-runtime-dependency POM, main
archive with MIT license, sources and strict Javadoc. A copied standalone Maven
project with a fresh cache resolves all four artifacts and executes all three
consumer-guide examples with its own codes. Snapshot and RC local qualification
also pass for concurrency, whose existing release/tag is unchanged.

The explicit component selector binds artifact/module/package/fixture and
component-tag admission. Signed bundle/recovery contracts run for both components;
45 offline tests pass. The regular CI consumer job now covers both components.
This is local file-repository packaging evidence, not Central/GitHub publication
or a second production-service integration. D4 final admission and explicitly
authorized D5 publication remain pending. See the
[D3 record](../libs/lib-2-diagnostics-design.md#d3-independent-packaging-record--2026-10-18).

### LIB-2 D4 final admission — 2026-10-18

[ADR 0044](../../../ADR/0044-reusable-diagnostics-contract.md) accepts the
implemented 18-type contract and its service/publication boundaries. Public
member/package documentation and guide examples are reviewed; snapshot packaging
and the cold standalone consumer pass. The review clarifies sample order after
replacement and closes symbolic manifest/repository/recovery-file redirection
before publication side effects. There are 78 API tests and 51 publication
contracts. Analyzer/coverage source scopes, suite inventory and gate thresholds
remain unchanged. Final offline gates and raw-report dispositions are owned by
the [D4 record](../libs/lib-2-diagnostics-design.md#d4-final-admission-record--2026-10-18)
and exact-worktree tool evidence. D5 publication and separate remote consumers
remain pending; LIB-2 is not `published-qualified`.

## LIB-3 protected publication — 2026-10-21

The eight-type logging API is accepted in
[ADR 0045](../../../ADR/0045-reusable-slf4j-logging-contract.md). L3-0–L3-4 is
complete; the generic mechanisms live in `platform-logging-slf4j`, while IOC
catalogs, ETL integration, diagnostics disclosure and backend configuration
stay local. Tag `ioc-platform-logging-slf4j-v0.3.0-rc.1` fixes reviewed source
`5b9761b7ab0b3d6033e51a82fb735f1a3049ef08` after PR 17.

Protected validation run `39433697620` and publication run `39455105300`
used the same signed 20-file bundle and Central deployment. Separate cold plain
and Boot consumers passed against Central and GitHub Packages, including primary
hashes, selected-repository origin and negative SLF4J dependency checks. The
[release record](../libs/lib-3-logging-design.md#protected-publication-record--2026-10-21)
retains immutable identity, artifacts and limits. A second production service
and live collector/backend/load qualification are outside these fixture results.
Existing concurrency/diagnostics release identities remain unchanged.
