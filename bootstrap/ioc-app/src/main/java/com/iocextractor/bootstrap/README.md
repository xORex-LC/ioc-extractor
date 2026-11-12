# Bootstrap composition package

## Purpose and navigation

Spring-facing composition, strict external configuration, runtime coordination
and operational projections. Concrete adapters may be wired here; core use cases
must not import this package. The flat package currently contains all groups
below. These sections are a reading map for the existing code, not new Java
package boundaries. The [module README](../../../../../../README.md) owns the assembly overview.

## Configuration model and selectors

| Files | Responsibility |
|---|---|
| [IocProperties](IocProperties.java), [IocProcessingProperties](IocProcessingProperties.java) | Typed external IOC tree and recursive named-plan declarations; shape is owned here, not in core. |
| [ConfigSelector](ConfigSelector.java), [ConfigSelectors](ConfigSelectors.java), [EngineType](EngineType.java), [RuntimeMode](RuntimeMode.java), [ObservabilityMode](ObservabilityMode.java), [StorageType](StorageType.java) | Canonical selector tokens and early reads before record binding. |
| [ArtifactIdStrategy](ArtifactIdStrategy.java), [ArtifactKeyMode](ArtifactKeyMode.java), [IdStart](IdStart.java), [IngestionLedgerType](IngestionLedgerType.java), [PipelineFailurePolicy](PipelineFailurePolicy.java) | Artifact ID/key grammar, ingestion backend and pipeline failure policy. |
| [ExportOutputMode](ExportOutputMode.java), [ExportTriggerType](ExportTriggerType.java), [RetentionActionType](RetentionActionType.java), [LifecycleValidityMode](LifecycleValidityMode.java), [ExistingRecordsPolicy](ExistingRecordsPolicy.java), [SyncTransport](SyncTransport.java), [SmbEncryptionMode](SmbEncryptionMode.java) | Typed export, lifecycle, maintenance and transport selectors. |

## Binding, inspection and controlled failures

| Files | Responsibility |
|---|---|
| [ConfigPreflightConfiguration](ConfigPreflightConfiguration.java), [IocConfigPreflight](IocConfigPreflight.java) | Converters plus Bean Validation/collect-all semantic checks; invalid operator input must reach this reporting boundary. |
| [IocConfigurationPropertyShape](IocConfigurationPropertyShape.java), [IocEnvironmentPropertyMatcher](IocEnvironmentPropertyMatcher.java), [IocUnknownConfigurationPreflight](IocUnknownConfigurationPreflight.java) | Strict IOC names over YAML/overlays/CLI/system properties and reserved IOC_* environment declarations. |
| [ConfigRegistryCatalog](ConfigRegistryCatalog.java), [ConfigRegistryPreflight](ConfigRegistryPreflight.java) | Provider/transform/filter/predicate catalogs and eager reference/plan admission. |
| [IocYamlSyntaxCheck](IocYamlSyntaxCheck.java), [IocYamlFailureDetails](IocYamlFailureDetails.java), [IocYamlConfigurationFailureAnalyzer](IocYamlConfigurationFailureAnalyzer.java) | Internal syntax command and controlled numeric YAML-location failures. |
| [IocSemanticConfigurationCheck](IocSemanticConfigurationCheck.java), [IocConfigurationCheckFailure](IocConfigurationCheckFailure.java), [IocConfigurationFailureAnalyzer](IocConfigurationFailureAnalyzer.java) | Configuration-only check, value-free failure details, IOC codes and migration guidance. |
| [IocConfigurationOverrideReporter](IocConfigurationOverrideReporter.java), [ConfigurationCompatibilityAlias](ConfigurationCompatibilityAlias.java), [IocConfigurationMigrationCatalog](IocConfigurationMigrationCatalog.java) | First-external-declaration display and bounded compatibility alias reporting. |

## Identity, policy and runtime graph

| Files | Responsibility |
|---|---|
| [AppConfig](AppConfig.java), [LazyServiceStorage](LazyServiceStorage.java) | Extraction/storage/export/lifecycle composition and lazy service datasource/migration ownership. |
| [ArtifactIdentityConfigurationResolver](ArtifactIdentityConfigurationResolver.java), [ArtifactIdentityCompatibilityReporter](ArtifactIdentityCompatibilityReporter.java), [V020ArtifactIdentityCompatibility](V020ArtifactIdentityCompatibility.java) | Current identity resolution and isolated, observable v0.2.0 rollback/upgrade compatibility window. |
| [ArtifactPolicyCatalog](ArtifactPolicyCatalog.java), [CsvFormatConfiguration](CsvFormatConfiguration.java), [ExportPlanCatalog](ExportPlanCatalog.java) | Compile configured mutation policies, one CSV format and resolved immutable export plans before runtime use. |
| [ProcessingPolicyFingerprint](ProcessingPolicyFingerprint.java), [ProcessingPolicyIdentity](ProcessingPolicyIdentity.java), [DocumentProcessingPolicyAdmission](DocumentProcessingPolicyAdmission.java) | Content-affecting policy identity and durable admission of daemon policy changes. |
| [ConditionalOnFileLedger](ConditionalOnFileLedger.java), [ConditionalOnJdbcLedger](ConditionalOnJdbcLedger.java), [ConditionalOnServiceStorage](ConditionalOnServiceStorage.java) | Explicit graph conditions for daemon ledger selection and service storage. |

## Selected IOC processing

| Files | Responsibility |
|---|---|
| [ProcessingPlanCatalog](ProcessingPlanCatalog.java), [ProcessingPlanBindings](ProcessingPlanBindings.java), [ProcessingBranchCompiler](ProcessingBranchCompiler.java) | Compile named plans, destination/column references and required document selection. |
| [ProcessingConditionBinding](ProcessingConditionBinding.java), [ProcessingConditionCompiler](ProcessingConditionCompiler.java) | Bind recursive NOT and compile typed, ordered boolean expressions. |
| [IocRouterConfiguration](IocRouterConfiguration.java), [RouterPlanRegistration](RouterPlanRegistration.java), [RouterRuntimeConfiguration](RouterRuntimeConfiguration.java) | Register admitted IOC operations/plans and own the embedded Camel runtime. |
| [ProcessingView](ProcessingView.java), [IocProcessingOperations](IocProcessingOperations.java), [IocProcessingRouteAdapter](IocProcessingRouteAdapter.java), [DocumentProcessingAdapter](DocumentProcessingAdapter.java) | Per-invocation views, neutral operation catalog and document-to-application result mapping. |
| [RouterProcessedImportRowPreparer](RouterProcessedImportRowPreparer.java), [SelectedProcessedImportRowPreparer](SelectedProcessedImportRowPreparer.java) | Use the pinned processed-contract route and assemble authorized logical import rows. |
| [RouterMdcScopes](RouterMdcScopes.java), [RouterTraceBridge](RouterTraceBridge.java), [RouterHealthIndicator](RouterHealthIndicator.java) | Restore local correlation, forward value-free technical trace and expose runtime readiness. |

## Intake and lifecycle coordination

| Files | Responsibility |
|---|---|
| [CanonicalIntakeStartupCoordinator](CanonicalIntakeStartupCoordinator.java), [OrderedDocumentStartupRecovery](OrderedDocumentStartupRecovery.java) | Recover before intake; drain pre-hash document admission before ordinary source recovery. |
| [LifecycleDeadlineScheduler](LifecycleDeadlineScheduler.java), [LifecycleProjectionScheduler](LifecycleProjectionScheduler.java), [LifecycleHistoryRetentionScheduler](LifecycleHistoryRetentionScheduler.java) | Separate admission-gated expiry, generation convergence and history/receipt retention workers. |
| [ObservationRegistrationRetentionScheduler](ObservationRegistrationRetentionScheduler.java), [LifecycleControlEventListener](LifecycleControlEventListener.java) | Retain observation handshakes/ranks and translate lossy lifecycle hints into worker nudges. |
| [LifecycleRuntimeObserver](LifecycleRuntimeObserver.java), [LifecycleHealthIndicator](LifecycleHealthIndicator.java) | Aggregate lifecycle diagnostics/logs and read-only durable readiness/convergence view. |

## Managed import assembly

| Files | Responsibility |
|---|---|
| [DataframeImportConfiguration](DataframeImportConfiguration.java), [DataframeImportPropertyMapper](DataframeImportPropertyMapper.java), [DataframeImportRuntimeConfiguration](DataframeImportRuntimeConfiguration.java) | Bind/compile the catalog and wire source adapters, snapshots, workspaces, writers, ledgers and use cases. |
| [ManagedImportSourceAdapters](ManagedImportSourceAdapters.java), [RoutedManagedImportSourceLifecycle](RoutedManagedImportSourceLifecycle.java), [DataframeImportSnapshotResolver](DataframeImportSnapshotResolver.java) | Compose local/SMB ownership and resolve durable snapshots versus ephemeral local previews. |
| [ManagedDataframeImportRuntime](ManagedDataframeImportRuntime.java), [DataframeImportRuntimeLifecycle](DataframeImportRuntimeLifecycle.java), [DataframeImportRuntimeState](DataframeImportRuntimeState.java) | Own recovery, reconcile/retention scheduling, bounded lanes and shutdown state. |
| [DataframeImportSnapshotPinnedListener](DataframeImportSnapshotPinnedListener.java), [DataframeImportLaneObserver](DataframeImportLaneObserver.java), [DataframeImportRuntimeObserver](DataframeImportRuntimeObserver.java) | Coalesced hints and value-free runtime degradation/diagnostics. |
| [LoggingDataframeImportObserver](LoggingDataframeImportObserver.java), [DataframeImportMetrics](DataframeImportMetrics.java), [DataframeImportHealthIndicator](DataframeImportHealthIndicator.java) | Durable-checkpoint ECS events, low-cardinality gauges and indexed status/readiness view. |

## Export, sync and maintenance

| Files | Responsibility |
|---|---|
| [DaemonExportScheduler](DaemonExportScheduler.java), [ExportNudgePolicy](ExportNudgePolicy.java), [ExportNudgeTrigger](ExportNudgeTrigger.java), [CanonicalArtifactsChangedExportListener](CanonicalArtifactsChangedExportListener.java) | Bounded independent profile formation and coalesced cadence nudges. |
| [SyncConfig](SyncConfig.java), [SyncSelectionCatalog](SyncSelectionCatalog.java), [TransportRegistry](TransportRegistry.java) | Bind enabled selections, lazy sync graph and transport lifecycle dispatch. |
| [RemoteFetchDetectionCoordinator](RemoteFetchDetectionCoordinator.java), [RemoteFetchDetectionTrigger](RemoteFetchDetectionTrigger.java), [RemoteFetchDetectionReason](RemoteFetchDetectionReason.java) | Coalesce startup/periodic/push detection per source and maintain transport idle leases. |
| [RemoteChangeWatchLifecycle](RemoteChangeWatchLifecycle.java), [RemoteChangeWatchStatus](RemoteChangeWatchStatus.java), [DaemonFetchScheduler](DaemonFetchScheduler.java), [RemoteChangeFetchListener](RemoteChangeFetchListener.java) | Drive optional watches, periodic detection and endpoint-keyed fetch command hand-off. |
| [DaemonPublishScheduler](DaemonPublishScheduler.java), [SliceCompletedPublishListener](SliceCompletedPublishListener.java), [DaemonSliceRetentionScheduler](DaemonSliceRetentionScheduler.java) | Reconcile durable publish work, deliver by endpoint and protect immutable slice retention. |
| [PeriodicDaemonCycle](PeriodicDaemonCycle.java), [DaemonMaintenanceScheduler](DaemonMaintenanceScheduler.java) | Fixed-delay lifecycle/non-overlap helper and separate leaf-file maintenance trigger. |
| [SyncHealthState](SyncHealthState.java), [SyncHealthIndicator](SyncHealthIndicator.java), [SyncOperationalOutcomePolicy](SyncOperationalOutcomePolicy.java), [SyncOperationalStatus](SyncOperationalStatus.java), [SyncKeyedExecutorObserver](SyncKeyedExecutorObserver.java) | Latest in-memory outcomes, durable backlog/pins, failure severity and executor observations. |
| [SmbSessionDemandPlanner](SmbSessionDemandPlanner.java), [SmbTransportHealthIndicator](SmbTransportHealthIndicator.java), [SmbTransportMetrics](SmbTransportMetrics.java) | Configured owned-session demand and adapter telemetry projections; no server quota discovery. |

## Logging, process lifecycle and other health views

| Files | Responsibility |
|---|---|
| [DaemonWebEnvironmentPostProcessor](DaemonWebEnvironmentPostProcessor.java), [ApplicationLifecycleLogger](ApplicationLifecycleLogger.java) | Early runtime defaults and events after logging initialization. |
| [EventCoordinationConfig](EventCoordinationConfig.java), [ControlEventObservations](ControlEventObservations.java), [LoggingControlEventObserver](LoggingControlEventObserver.java) | Wire the shared Spring event publisher and keep handler observation best-effort. |
| [IocEcsStructuredLogEncoder](IocEcsStructuredLogEncoder.java), [LoggingPipelineDecisionTracer](LoggingPipelineDecisionTracer.java), [LoggingExportObserver](LoggingExportObserver.java) | Boot nested ECS dataset handling, doubly gated item trace and export checkpoints. |
| [ArtifactStorageHealthIndicator](ArtifactStorageHealthIndicator.java), [IngestionLedgerHealthIndicator](IngestionLedgerHealthIndicator.java), [JdbcStorageHealthIndicator](JdbcStorageHealthIndicator.java) | Local directory/file-ledger checks and storage-adapter JDBC health projection. |
| [IngestionLifecycleHealthIndicator](IngestionLifecycleHealthIndicator.java), [ObservationRegistrationHealthIndicator](ObservationRegistrationHealthIndicator.java), [DataProcessingCapacityHealthIndicator](DataProcessingCapacityHealthIndicator.java), [ExportHealthIndicator](ExportHealthIndicator.java) | Recovery/intake, unresolved observation ranks, bounded processing counters and export freshness. |
| [package-info](package-info.java) | Package boundary; exact type/member guarantees belong in Javadoc. |

## Configuration admission

`IocProperties` supplies the schema; classpath YAML and deployed overrides supply
values. Shared property traversal/inspection comes from the configuration library.
IOC spelling rules, reserved environment-prefix handling, semantic checks,
registry policy and legacy migration hints remain service-owned. Constructors
snapshot/default the model; semantic validation collects operator mistakes before
runtime side effects. Higher-priority Boot lists replace complete lower lists.

`IocConfigurationOverrideReporter` records the first external declaration of a
name without reading its value. It is not exact final Binder provenance, notably
for list replacement and null declarations. Failure analyzers deliberately omit
input-bearing exception/parser details and dynamic keys. The configuration-only
checker still treats ConfigData imports as trusted deployment inputs; it does
not scan runtime beans or qualify IO. See
[configuration internals](../../../../../../../../docs/dev/configuration.md) and
[controlled failure reporting](../../../../../../../../docs/dev/configuration-failure-reports.md).

The v0.2.0 identity adapter and old SMB `encrypt` alias are bounded compatibility
paths, not fresh-template defaults. Keep them until their supported rollback
window ends. New/current identity definitions go through the permanent resolver.

## Graph ownership and lazy resolution

`AppConfig` assembles extraction, canonical storage, lifecycle and export.
`SyncConfig` owns sync plus shared SMB settings/pool/watcher/telemetry when either
sync or import needs them. `DataframeImportRuntimeConfiguration` wires enabled
import using the same pool and injected local snapshot store. A disabled ordinary
sync path does not imply that import has no transport beans.

`LazyServiceStorage` deliberately is not a `DataSource`; unrelated type discovery
cannot open it. It owns service-pool initialization, migration and close. Daemon
explicitly exposes the resolved service datasource with destruction disabled on
the borrowed bean. Export/sync backed use cases are lazy, and CLI selection
preflight occurs before obtaining them. Stateful extract may open dataframe
storage without opening service SQLite. Defaults are not a blanket promise of
zero IO for any arbitrary bean lookup.

Dataframe storage owns canonical/lifecycle/history/identity/export-slot/import
commit facts; service storage owns coordination. Explicit migration/reconciliation
and identity-validation dependencies precede use. One shared fair
`JdbcWriterAdmission` coordinates local canonical mutations, expiry, slots and
projection acknowledgements; it does not replace SQLite transactions or provide
cross-process fencing. `GenerationOwnedArtifactProjection` is the exposed
projection port; raw `CsvArtifactProjection` is a private installer, not a second
writable bean. Schema/version/rollback details belong to
[storage](../../../../../../../../docs/dev/storage.md) and the [JDBC adapter](../../../../../../../../adapters/adapter-store-jdbc/README.md).
Migration cleanup's known unchecked-failure limit remains [OPS-15](../../../../../../../../docs/KNOWN-ISSUES.md#ops-15).

`ExportPlanCatalog` resolves enabled artifacts, ordered schema/identity/mapping,
CSV policy and external-slot policy without IO. Its hashes pin plan semantics.
Actual immutable-slice creation/recovery acquires the application operation guard
and uses service ledgers plus dataframe snapshot/slot authority. No separate
service-DB slot registry is wired here.

## Processing and import contracts

Every document path selects a named admitted plan; processed import contracts
select an explicit `processed-route` even when intake is disabled. Catalog
admission checks registered operations/predicates, views, output columns and
references before runtime activation. The embedded Camel runtime is required for
the selected IOC processing graph, not an automatic fallback to an older preparer.
`DocumentProcessingAdapter` uses the invocation's preparers; processed import
assembles authorized cells without deciding canonical identity itself.

The processing fingerprint pins content-affecting policy. Daemon policy changes
must pass the durable processing-policy gate after earlier work drains; an import
retry must retain its pinned contract. Technical routing outcomes/trace do not
replace application diagnostics or canonical admission. See
[processing](../../../../../../../../docs/dev/processing.md), [processing-route configuration](../../../../../../../../docs/guides/ioc-processing-routes.md)
and [managed-import internals](../../../../../../../../docs/dev/dataframe-import.md).

Import graph construction separates configuration/catalog work, command use
cases and daemon runtime lanes. Enabled intake requires its validated daemon,
storage and fixed-lifecycle prerequisites. The runtime owns change-signal handles,
reconcile/retention scheduler and keyed lanes; shutdown closes those owners in
order. Source capability gates and receipts remain authoritative after restart;
a snapshot hint is only a nudge. Local/SMB adapters own source claims and bytes.

## Startup barriers and background work

Spring eager singleton construction is only the first daemon barrier.
`CanonicalIntakeStartupCoordinator` is the highest-precedence runner: require
closed intake, recover ingest-run projections, recover ordered document admission
and incomplete sources, prepare lifecycle admission, recover/start import,
start document dispatch, then open the ordinary ingestion flow. Failure stops
owned intake/runtime components and propagates rather than advertising readiness.

Export and lifecycle workers register against `CanonicalDataAdmissionState` and
remain inactive until admission opens. Export forward recovery finishes before
its scheduling starts. The table describes lifecycle start phases; it does not
replace the runner/admission barriers or mean that all workers are started by
the same mechanism.

| Worker / owner | Lifecycle boundary |
|---|---|
| Remote change watches | SmartLifecycle phase 45; dedicated adapter handles |
| Fetch trigger / lifecycle deadline | Phase 50; separate detection and nearest-deadline loops |
| Lifecycle history / mutable projection | Phases 55 / 60; independent history/receipt and generation backstops |
| Export formation | Phase 100; two workers, queue bounded by profile catalog, at most one queued/running attempt per profile |
| Publish | Phase 150; synchronous startup ledger reconciliation without remote writes, then delivery attempts |
| Immutable slice retention | Phase 200; later than formation/publish, separate from leaf maintenance |
| Document/import intake | Explicit startup coordinator; do not infer readiness from a SmartLifecycle phase |

Periodic detection and optional push enter the same per-source coalescing
coordinator. Fetch/publish hand-off uses a bounded keyed executor per endpoint;
different endpoints can progress concurrently. Profile export can progress
independently, but its local exclusion is not the durable single-flight/lease.
Post-commit `CanonicalArtifactsChanged`/`SliceCompleted` hints shorten latency;
ledgers and periodic reconcile recover lost events and process restarts.

Deadline, projection, history/receipt, observation-registration, import,
immutable-slice and leaf-file retention each have their own scope. In particular,
expiry is not a new-row revision or automatic immutable export. Active publish
uses a ledger-based slice-retention guard; standalone retention is selected when
that delivery graph is absent. Avoid reaping service-owned import files with the
legacy recursive leaf reaper. Worker stop budgets are local implementation limits,
not a universal guarantee for blocked external IO or forced process termination.
See [ingestion](../../../../../../../../docs/dev/ingestion.md), [lifecycle](../../../../../../../../docs/dev/canonical-record-lifecycle.md),
[export](../../../../../../../../docs/dev/artifact-export.md) and [event coordination](../../../../../../../../docs/dev/event-coordination.md).

## Observability and verification

Health combines local configuration/runtime snapshots and explicit durable read
models. It can expose `UP`, recoverable `DEGRADED` and `DOWN`; readiness, latest
remote outcomes and durable backlog are distinct observations. SMB demand and
telemetry describe owned resources, not discovered server capacity. Read-only
health is not an ACL audit or repair command. Keep detailed health loopback-only
under the shipped policy; [health/metrics](../../../../../../../../docs/guides/daemon-operations.md) owns the
operational model and [known issues](../../../../../../../../docs/KNOWN-ISSUES.md) retains unresolved limits.

`EventCoordinationConfig` constructs the shared `SpringControlEventPublisher`;
the implementation lives in the events adapter. IOC listeners and observation
policy stay here. Item tracing requires both the config switch and logger TRACE;
router hooks remain value-free and local MDC scopes restore ambient context.
`IocEcsStructuredLogEncoder` contributes `event.dataset` through Boot's nested ECS
context-pair mechanism. [Logging](../../../../../../../../docs/dev/observability.md) owns field semantics.

Use the [bootstrap test map](../../../../../test/java/com/iocextractor/bootstrap/README.md)
for focused suites and the [module test map](../../../../../test/java/com/iocextractor/README.md)
for composed scenarios. Full `make verify` includes architecture and wire consumers;
it does not establish live-server, installation or load acceptance.
