# Application composition tests

## Purpose and structure

This module tests the assembled service and repository contracts. Test package
names mirror collaborators for access/organization; the domain-named tests here
are integration points with concrete regex/PSL adapters, not the pure domain
module's own source tree.

| Location | Responsibility |
|---|---|
| This package | Cross-module architecture/release contracts and composed runtime scenarios |
| [bootstrap](bootstrap/README.md) | Configuration, wiring, scheduling, health, policy and recovery fixtures |
| [consumer](consumer/README.md) | Independent reader for public CSV/immutable-slice wires |
| [domain](domain/README.md) | Domain behavior exercised with concrete integration engines |
| [observability](observability/README.md) | Production encoder/XML and isolated library consumer recipes |
| [Resources](../../../resources/README.md) | Test profiles, exact expected bytes and consumer programs |

## Local suite map

| Files | Responsibility |
|---|---|
| [ArchitectureTest](ArchitectureTest.java), [CatalogReferenceRatchetTest](CatalogReferenceRatchetTest.java) | Production dependency/package/registry contracts; architectural tags do not qualify deployment. |
| [DocumentationConventionTest](DocumentationConventionTest.java), [MavenVersionConventionTest](MavenVersionConventionTest.java), [ReleaseNotesContractTest](ReleaseNotesContractTest.java), [BuildInfoContractTest](BuildInfoContractTest.java) | Module README presence, version/release-note mechanics and generated build identity. Presence does not prove README correctness. |
| [ApplicationContextIT](ApplicationContextIT.java), [OneshotLazyInitializationIT](OneshotLazyInitializationIT.java) | Default graph and lazy command construction without premature JDBC initialization. |
| [DaemonRuntimeModeIT](DaemonRuntimeModeIT.java), [DaemonManagementEndpointIT](DaemonManagementEndpointIT.java), [DataframeStorageDaemonRuntimeModeIT](DataframeStorageDaemonRuntimeModeIT.java), [JdbcLedgerDaemonRuntimeModeIT](JdbcLedgerDaemonRuntimeModeIT.java), [SyncEnabledDaemonRuntimeModeIT](SyncEnabledDaemonRuntimeModeIT.java) | Daemon, loopback actuator, explicit storage/ledger choices and enabled sync/import graph; no live SMB qualification. |
| [GoldenPipelineIT](GoldenPipelineIT.java), [CustomerRoutingPipelineIT](CustomerRoutingPipelineIT.java) | Synthetic pipeline/golden wires and explicitly selected customer routing. |
| [DaemonIngestE2EIT](DaemonIngestE2EIT.java), [OnDemandExportIntegrationIT](OnDemandExportIntegrationIT.java), [ReusableExportSlotIntegrationIT](ReusableExportSlotIntegrationIT.java) | Composed intake, immutable export/recovery and sparse reusable slot behavior. |

## Discovery, isolation and limits

Surefire owns `*Test` classes, including ArchUnit; Failsafe owns the `*IT`
configuration/integration/E2E classes. All Failsafe suites carry the corresponding
shared semantic annotations. Fixtures use temporary paths or explicitly redirected
test output directories, never operator inputs. Some scenarios use forked JVMs
and a random-port local HTTP server. No local suite requires a provisioned SMB
server or external credentials; mocked/fake transport seams are identified in
the more specific maps.

Golden results and reference consumers validate supported observable wires with
independent expectations. Architecture tests check the named boundaries, not all
possible architectural risks. Documentation/release convention tests check
mechanics and selected content contracts, not complete semantic review or release
readiness. Passing a local scenario is not reference-load, power-loss, installed
service or live-server qualification.

## Verification

Run from the repository root:

```bash
make test-module MODULE=bootstrap/ioc-app
make test-integration-module MODULE=bootstrap/ioc-app
```

Full reactor acceptance uses `make verify`. Use `make test-one` for focused
selectors; the facade chooses the lifecycle from the class name. Retain worker/
JVM timeout and cleanup evidence; do not add retries to hide a failure. Test and
coverage policy lives in [TESTING.md](../../../../../../../docs/TESTING.md).
