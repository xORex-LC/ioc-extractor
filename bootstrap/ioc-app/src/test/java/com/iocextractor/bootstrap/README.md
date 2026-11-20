# Bootstrap tests

## Purpose and reading map

Tests for the [production composition package](../../../../../main/java/com/iocextractor/bootstrap/README.md).
Start with the group matching the graph segment you are changing, then run the
composed scenarios from the [parent test map](../README.md).
The `sync` child directory currently contains only its property-binding suite.

## Configuration and identity

| Files | Responsibility |
|---|---|
| [IocPropertiesTest](IocPropertiesTest.java), [IocPropertiesBindingIT](IocPropertiesBindingIT.java), [IocConfigPreflightTest](IocConfigPreflightTest.java), [IdStartTest](IdStartTest.java) | Typed model/defaults, real binding and semantic/value grammar. |
| [IocUnknownConfigurationPreflightTest](IocUnknownConfigurationPreflightTest.java), [IocConfigurationPropertyShapeTest](IocConfigurationPropertyShapeTest.java), [IocConfigurationFailureAnalyzerTest](IocConfigurationFailureAnalyzerTest.java), [IocYamlConfigurationFailureAnalyzerTest](IocYamlConfigurationFailureAnalyzerTest.java), [IocYamlSyntaxCheckTest](IocYamlSyntaxCheckTest.java) | Strict names, IOC/environment shape and value-free failure boundaries. |
| [IocSemanticConfigurationCheckTest](IocSemanticConfigurationCheckTest.java), [IocConfigurationContextIT](IocConfigurationContextIT.java), [ConfigurationCheckRecipeIT](ConfigurationCheckRecipeIT.java) | Minimal checker, actual Boot ConfigData/forked JVM behavior and independent closed-input guide consumer. |
| [IocConfigurationOverrideReporterTest](IocConfigurationOverrideReporterTest.java), [ConfigurationDocumentationContractTest](ConfigurationDocumentationContractTest.java), [ConfigRegistryPreflightIT](ConfigRegistryPreflightIT.java) | Declaration reporting, operator guide/template/metadata coverage and eager registry checks. |
| [ArtifactIdentityConfigurationResolverTest](ArtifactIdentityConfigurationResolverTest.java), [ArtifactIdentityCompatibilityReporterTest](ArtifactIdentityCompatibilityReporterTest.java) | Current identity mapping and observed bounded compatibility. |
| [PatternEngineConfigurationTest](PatternEngineConfigurationTest.java), [ExactNetworkCellParsingTest](ExactNetworkCellParsingTest.java) | Production-configured RE2/J/JDK corpus and exact IOC cell boundaries. |
| [sync/SyncPropertiesTest.java](sync/SyncPropertiesTest.java) | Sync property defaults and binding checks in the small sync subpackage. |

## Routing and canonical output

| Files | Responsibility |
|---|---|
| [ProcessingPlanCatalogTest](ProcessingPlanCatalogTest.java), [DocumentProcessingAdapterTest](DocumentProcessingAdapterTest.java), [RouterProcessedImportRowPreparerTest](RouterProcessedImportRowPreparerTest.java) | Typed views/conditions/branch output, document mapping and authorized processed rows. |
| [RouterRuntimeConfigurationIT](RouterRuntimeConfigurationIT.java), [IocRouterConfigurationIT](IocRouterConfigurationIT.java) | Required admitted Camel runtime, IOC registration, readiness and close ownership. |
| [RouterSelectedImportDeliveryIT](RouterSelectedImportDeliveryIT.java), [PreparedDocumentPromotionIT](PreparedDocumentPromotionIT.java) | Production-selected import/recovery and canonical winner ordering when preparation finishes out of order. |
| [ExportPlanCatalogTest](ExportPlanCatalogTest.java), [DataframeRecoveryIntegrationIT](DataframeRecoveryIntegrationIT.java), [MutableProjectionOwnershipIT](MutableProjectionOwnershipIT.java), [NativeDeliveryLifecycleIT](NativeDeliveryLifecycleIT.java) | Plan/hash validation, durable recovery, generation-owned projections and native delivery/lifecycle integration. |

## Workers and recovery barriers

| Files | Responsibility |
|---|---|
| [CanonicalIntakeStartupCoordinatorTest](CanonicalIntakeStartupCoordinatorTest.java), [ObservationRegistrationOperationsTest](ObservationRegistrationOperationsTest.java) | Startup ordering/failure cleanup and registration retention/health behavior. |
| [DaemonWebEnvironmentPostProcessorTest](DaemonWebEnvironmentPostProcessorTest.java), [LazyServiceStorageTest](LazyServiceStorageTest.java) | Runtime-mode/lazy defaults and deferred service-pool lifetime. |
| [DaemonExportSchedulerTest](DaemonExportSchedulerTest.java), [CanonicalArtifactsChangedExportListenerTest](CanonicalArtifactsChangedExportListenerTest.java) | Per-profile bounded execution, cadence/nudges, admission/recovery and stop behavior. |
| [LifecycleDeadlineSchedulerTest](LifecycleDeadlineSchedulerTest.java), [LifecycleHistoryRetentionSchedulerTest](LifecycleHistoryRetentionSchedulerTest.java) | Nearest-deadline hints/backstop and independent bounded retention. |
| [ManagedDataframeImportRuntimeTest](ManagedDataframeImportRuntimeTest.java), [DataframeImportSmbRuntimeConfigurationIT](DataframeImportSmbRuntimeConfigurationIT.java), [DataframeImportLaneObserverTest](DataframeImportLaneObserverTest.java) | Import startup/shutdown, import-only shared SMB wiring without network IO and failed-lane degradation. |
| [PeriodicDaemonCycleTest](PeriodicDaemonCycleTest.java), [DaemonFetchSchedulerTest](DaemonFetchSchedulerTest.java), [RemoteFetchDetectionCoordinatorTest](RemoteFetchDetectionCoordinatorTest.java), [RemoteChangeWatchLifecycleTest](RemoteChangeWatchLifecycleTest.java) | Fixed-delay cycles, source-isolated startup/poll/push coalescing and optional watch lifetime. |
| [RemoteChangeFetchListenerTest](RemoteChangeFetchListenerTest.java), [DaemonPublishSchedulerTest](DaemonPublishSchedulerTest.java), [SliceCompletedPublishListenerTest](SliceCompletedPublishListenerTest.java), [DaemonSliceRetentionSchedulerTest](DaemonSliceRetentionSchedulerTest.java) | Endpoint hand-off, ledger reconcile/event backstop and retention coordination. |
| [TransportRegistryTest](TransportRegistryTest.java), [SyncSelectionCatalogTest](SyncSelectionCatalogTest.java) | Endpoint dispatch/lifecycle and IO-free selection failure before lazy graph resolution. |

## Operational projections and test infrastructure

| Files | Responsibility |
|---|---|
| [DataProcessingCapacityHealthIndicatorTest](DataProcessingCapacityHealthIndicatorTest.java), [IngestionLifecycleHealthIndicatorTest](IngestionLifecycleHealthIndicatorTest.java), [LifecycleHealthIndicatorTest](LifecycleHealthIndicatorTest.java), [ExportHealthIndicatorTest](ExportHealthIndicatorTest.java), [DataframeImportHealthIndicatorTest](DataframeImportHealthIndicatorTest.java) | Safe local aggregate counters, runtime/durable readiness, export lag and import source status. |
| [SyncHealthIndicatorTest](SyncHealthIndicatorTest.java), [SmbTransportHealthIndicatorTest](SmbTransportHealthIndicatorTest.java), [SmbTransportMetricsTest](SmbTransportMetricsTest.java) | Sync durable/in-memory views and owned SMB resource/demand metrics. |
| [SyncKeyedExecutorObserverTest](SyncKeyedExecutorObserverTest.java), [SyncOperationalOutcomePolicyTest](SyncOperationalOutcomePolicyTest.java), [EventCoordinationConfigTest](EventCoordinationConfigTest.java) | Executor degradation, transport outcome severity and shared event-adapter wiring. |
| [LoggingControlEventObserverTest](LoggingControlEventObserverTest.java), [LoggingDataframeImportObserverTest](LoggingDataframeImportObserverTest.java), [LoggingExportObserverTest](LoggingExportObserverTest.java), [LoggingPipelineDecisionTracerTest](LoggingPipelineDecisionTracerTest.java) | Stable value-free checkpoint fields, trace gates and observation failure behavior. |
| [LifecycleRuntimeObserverTest](LifecycleRuntimeObserverTest.java), [DataframeImportRuntimeObserverTest](DataframeImportRuntimeObserverTest.java) | Aggregate runtime diagnostics and meaningful/silent event boundaries. |
| [AsyncTestSupport](AsyncTestSupport.java) | Timed asynchronous test coordination and bounded waits; not a production helper. |

## Verification and evidence limits

Unit tests use bounded fakes/counters/temporary resources where the contract is
local. Integration tests bind actual IOC configuration, construct real storage/
Camel contexts or compile and fork a small consumer. `NativeDeliveryLifecycleIT`
replaces the SMB wire boundary and clock; `DataframeImportSmbRuntimeConfigurationIT`
checks import-only composition without network IO. Neither is live SMB evidence.

`IocConfigurationContextIT` qualifies actual ConfigData loading/list replacement
and the minimal semantic graph. The closed-input recipe deliberately excludes
ambient sources and IOC classes, so it qualifies a different input policy; it is
not a cold Maven-repository consumption proof. The configuration-documentation
contract checks selected reflection/hint/template coverage; it does not replace
manual documentation review.

Run from the repository root, for example:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=CanonicalIntakeStartupCoordinatorTest
make test-one MODULE=bootstrap/ioc-app TEST=IocConfigurationContextIT
```

Use `make test-module MODULE=bootstrap/ioc-app` and
`make test-integration-module MODULE=bootstrap/ioc-app` for both local cohorts,
then `make verify` for the reactor gate. Timing/worker cleanup and coverage rules
belong to [TESTING.md](../../../../../../../../docs/TESTING.md). Focused tests do not close unrelated release,
security, deployment or capacity qualification.
