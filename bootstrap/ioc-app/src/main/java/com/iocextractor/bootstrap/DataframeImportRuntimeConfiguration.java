package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.csv.CommonsCsvDelimitedRecordReader;
import com.iocextractor.adapter.in.csv.CommonsCsvImportValueTransformRegistry;
import com.iocextractor.adapter.in.csv.CsvProcessedImportRowPreparer;
import com.iocextractor.adapter.in.csv.ImportSnapshotPathResolver;
import com.iocextractor.adapter.in.cli.ImportPreviewFileLocator;
import com.iocextractor.adapter.in.ingest.LocalImportChangeSignalSource;
import com.iocextractor.adapter.in.ingest.LocalImportSourceDefinition;
import com.iocextractor.adapter.in.ingest.LocalImportSnapshotPathResolver;
import com.iocextractor.adapter.in.ingest.LocalImportTerminalStore;
import com.iocextractor.adapter.in.ingest.LocalManagedImportSourceLifecycle;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalImportWriter;
import com.iocextractor.adapter.out.store.jdbc.JdbcImportCommitEvidenceStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcImportDeliveryLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcImportStatusReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcImportWorkspace;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleClock;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import com.iocextractor.adapter.out.store.jdbc.SchemaMigrationResult;
import com.iocextractor.adapter.out.transport.smb.SmbChangeNotifyWatcher;
import com.iocextractor.adapter.out.transport.smb.SmbImportChangeSignalSource;
import com.iocextractor.adapter.out.transport.smb.SmbImportSourceDefinition;
import com.iocextractor.adapter.out.transport.smb.SmbManagedImportSourceLifecycle;
import com.iocextractor.adapter.out.transport.smb.SmbSessionPool;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.classification.IndicatorClassifier;
import com.iocextractor.application.dataframeimport.DataframeImportAdmissionService;
import com.iocextractor.application.dataframeimport.DataframeImportDetectionCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportDetectionService;
import com.iocextractor.application.dataframeimport.DataframeImportDrainCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportProcessingService;
import com.iocextractor.application.dataframeimport.DataframeImportPromotionService;
import com.iocextractor.application.dataframeimport.DataframeImportRecoveryService;
import com.iocextractor.application.dataframeimport.DataframeImportRecoveryCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportReplayService;
import com.iocextractor.application.dataframeimport.DataframeImportRetentionService;
import com.iocextractor.application.dataframeimport.DataframeImportStagingService;
import com.iocextractor.application.dataframeimport.DataframeImportStatusService;
import com.iocextractor.application.dataframeimport.DataframeImportValidationService;
import com.iocextractor.application.dataframeimport.EventPublishingCanonicalImportWriter;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import com.iocextractor.application.dataframeimport.contract.DataframeImportRecognizer;
import com.iocextractor.application.dataframeimport.mapping.DataframeImportRowMapper;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.QueryDataframeImportStatusUseCase;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.RunDataframeImportRetentionUseCase;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportReplaySnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportReportStore;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalRetentionStore;
import com.iocextractor.application.port.out.dataframeimport.ImportValueTransformRegistry;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.concurrent.BoundedKeyedSerialExecutor;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Runtime composition for the disabled-by-default managed dataframe import. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServiceStorage
@ConditionalOnProperty(prefix = "ioc.dataframe-import", name = "enabled", havingValue = "true")
class DataframeImportRuntimeConfiguration {

    @Bean
    ImportWorkspaceLimits dataframeImportWorkspaceLimits() {
        return ImportWorkspaceLimits.defaults();
    }

    @Bean
    ManagedImportSourceAdapters managedImportSourceAdapters(
            DataframeImportCatalog catalog,
            IocProperties properties,
            SmbSessionPool smbSessions,
            SmbChangeNotifyWatcher smbWatcher) {
        List<LocalImportSourceDefinition> localSources = catalog.sources().entrySet().stream()
                .filter(entry -> entry.getValue().transport() == ImportSourceTransport.LOCAL)
                .map(entry -> new LocalImportSourceDefinition(
                        entry.getKey(), Path.of(entry.getValue().location())))
                .toList();
        List<SmbImportSourceDefinition> smbSources = catalog.sources().entrySet().stream()
                .filter(entry -> entry.getValue().transport() == ImportSourceTransport.SMB)
                .map(entry -> new SmbImportSourceDefinition(
                        entry.getKey(), entry.getValue().endpoint(), entry.getValue().location()))
                .toList();
        IocProperties.DataframeImport.RuntimeSettings runtime = properties.dataframeImport().runtime();
        Map<ImportSourceId, ManagedImportSourceLifecycle> routes = new LinkedHashMap<>();
        List<ImportSnapshotPathResolver> resolvers = new ArrayList<>();
        List<ImportChangeSignalSource> signals = new ArrayList<>();
        resolvers.add(new LocalImportSnapshotPathResolver(
                Path.of(runtime.dirs().snapshots()))::resolve);
        if (!localSources.isEmpty()) {
            var local = new LocalManagedImportSourceLifecycle(
                    localSources,
                    Path.of(runtime.dirs().processing()),
                    Path.of(runtime.dirs().snapshots()),
                    Path.of(runtime.dirs().terminal()),
                    Path.of(runtime.dirs().quarantine()),
                    runtime.stability().quietPeriod(),
                    runtime.limits().maximumSnapshotBytes());
            localSources.forEach(source -> routes.put(source.sourceId(), local));
            if (runtime.detect().useWatchService()) {
                signals.add(new LocalImportChangeSignalSource(localSources));
            }
        }
        if (!smbSources.isEmpty()) {
            var smb = new SmbManagedImportSourceLifecycle(
                    smbSources,
                    smbSessions,
                    Path.of(runtime.dirs().snapshots()),
                    runtime.stability().quietPeriod(),
                    runtime.limits().maximumSnapshotBytes());
            smbSources.forEach(source -> routes.put(source.sourceId(), smb));
            resolvers.add(smb::resolveSnapshot);
            if (runtime.detect().useChangeNotifications()) {
                signals.add(new SmbImportChangeSignalSource(smbSources, smbWatcher));
            }
        }
        var routed = new RoutedManagedImportSourceLifecycle(routes, resolvers);
        return new ManagedImportSourceAdapters(routed, routed, signals);
    }

    @Bean
    ManagedImportSourceLifecycle managedImportSourceLifecycle(
            ManagedImportSourceAdapters adapters) {
        return adapters.lifecycle();
    }

    @Bean
    ImportSnapshotPathResolver dataframeImportSnapshotPathResolver(
            ManagedImportSourceAdapters adapters,
            IocProperties properties) {
        return new DataframeImportSnapshotResolver(
                adapters.snapshots(),
                properties.dataframeImport().runtime().limits().maximumSnapshotBytes());
    }

    @Bean
    ImportPreviewFileLocator dataframeImportPreviewFileLocator(
            @Qualifier("dataframeImportSnapshotPathResolver") ImportSnapshotPathResolver snapshots) {
        return (DataframeImportSnapshotResolver) snapshots;
    }

    @Bean
    DelimitedRecordReader dataframeImportRecordReader(
            @Qualifier("dataframeImportSnapshotPathResolver") ImportSnapshotPathResolver snapshots) {
        return new CommonsCsvDelimitedRecordReader(snapshots);
    }

    @Bean
    ImportValueTransformRegistry dataframeImportTransforms() {
        return new CommonsCsvImportValueTransformRegistry(ConfigRegistryCatalog.transforms());
    }

    @Bean
    DataframeImportRecognizer dataframeImportRecognizer(
            DataframeImportCatalog catalog,
            DelimitedRecordReader reader) {
        return new DataframeImportRecognizer(catalog, reader);
    }

    @Bean
    ProcessedImportRowPreparer dataframeImportProcessedRowPreparer(
            AppConfig appConfig,
            IocProperties properties,
            ArtifactIdBaseline artifactIdBaseline,
            Refanger refanger,
            IndicatorExtractor extractor,
            MatchPolicy matchPolicy) {
        CanonicalArtifactKeyResolver keys = new CanonicalArtifactKeyResolver(
                appConfig.artifactIdentityDefinitions(properties));
        return new CsvProcessedImportRowPreparer(
                appConfig.artifactDefinitions(properties, artifactIdBaseline),
                refanger, extractor, new IndicatorClassifier(matchPolicy), keys);
    }

    @Bean
    DataframeImportRowMapper dataframeImportRowMapper(
            ImportValueTransformRegistry transforms,
            ProcessedImportRowPreparer processed,
            AppConfig appConfig,
            IocProperties properties) {
        return new DataframeImportRowMapper(
                transforms,
                new CanonicalArtifactKeyResolver(appConfig.artifactIdentityDefinitions(properties)),
                processed);
    }

    @Bean
    ImportWorkspace dataframeImportWorkspace(
            IocProperties properties,
            ImportWorkspaceLimits limits,
            Clock clock) {
        return new JdbcImportWorkspace(
                Path.of(properties.dataframeImport().runtime().dirs().staging()), limits, clock);
    }

    @Bean
    ImportDeliveryLedger dataframeImportDeliveryLedger(
            LazyServiceStorage serviceStorage,
            IocProperties properties) {
        serviceStorage.migration();
        var retention = properties.dataframeImport().runtime().retention();
        return new JdbcImportDeliveryLedger(
                serviceStorage.dataSource(),
                retention.successful().maxAge(), retention.unsuccessful().maxAge());
    }

    @Bean
    LocalImportTerminalStore dataframeImportTerminalStore(
            IocProperties properties,
            @Qualifier("dataframeImportSnapshotPathResolver") ImportSnapshotPathResolver snapshots) {
        var runtime = properties.dataframeImport().runtime();
        return new LocalImportTerminalStore(
                Path.of(runtime.dirs().terminal()),
                Path.of(runtime.dirs().quarantine()),
                Path.of(runtime.dirs().snapshots()),
                snapshots::resolve,
                runtime.limits().maximumSnapshotBytes());
    }

    @Bean
    ImportCommitEvidenceStore dataframeImportCommitEvidenceStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataSource,
            @Qualifier("dataframeFormatSchemaMigration") SchemaMigrationResult migration) {
        return new JdbcImportCommitEvidenceStore(dataSource);
    }

    @Bean
    CanonicalImportWriter canonicalImportWriter(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataSource,
            @Qualifier("dataframeFormatSchemaMigration") SchemaMigrationResult migration,
            ArtifactIdBaseline artifactIdBaseline,
            JdbcLifecycleClock lifecycleClock,
            JdbcWriterAdmission writerAdmission,
            ControlEventPublisher events,
            AppConfig appConfig,
            IocProperties properties,
            Clock clock) {
        Path workspaceRoot = Path.of(properties.dataframeImport().runtime().dirs().staging());
        var writer = new JdbcCanonicalImportWriter(
                dataSource,
                appConfig.dataframeSchemas(properties),
                appConfig.artifactIdAllocatorDefinitions(properties, artifactIdBaseline),
                appConfig.artifactIdentityDefinitions(properties),
                workspaceRoot,
                lifecycleClock,
                new FixedRecordValidityPolicy(properties.lifecycle().validity().fixedTtl()),
                clock,
                writerAdmission);
        return new EventPublishingCanonicalImportWriter(writer, events);
    }

    @Bean
    DataframeImportStagingService dataframeImportStagingService(
            DataframeImportRecognizer recognizer,
            DataframeImportRowMapper mapper,
            DelimitedRecordReader reader,
            ImportWorkspace workspace,
            ImportWorkspaceLimits limits) {
        return new DataframeImportStagingService(recognizer, mapper, reader, workspace, limits);
    }

    @Bean
    DataframeImportPromotionService dataframeImportPromotionService(
            ImportDeliveryLedger ledger,
            CanonicalImportWriter writer,
            Clock clock) {
        return new DataframeImportPromotionService(ledger, writer, clock);
    }

    @Bean
    DataframeImportAdmissionService dataframeImportAdmissionService(
            ImportDeliveryLedger ledger,
            ManagedImportSourceLifecycle sources,
            ControlEventPublisher events,
            LocalImportTerminalStore terminals,
            IocProperties properties,
            Clock clock) {
        return new DataframeImportAdmissionService(
                ledger, sources, events, clock,
                properties.dataframeImport().runtime().retry().delay(), terminals);
    }

    @Bean
    ProcessNextDataframeImportUseCase processNextDataframeImportUseCase(
            ImportDeliveryLedger ledger,
            DataframeImportStagingService staging,
            DataframeImportPromotionService promotion,
            ImportWorkspace workspace,
            ImportCommitEvidenceStore commits,
            LocalImportTerminalStore terminals,
            ManagedImportSourceLifecycle sources,
            IocProperties properties,
            Clock clock) {
        return new DataframeImportProcessingService(
                ledger, staging, promotion, workspace, commits, terminals, sources, clock,
                properties.dataframeImport().runtime().retry().delay());
    }

    @Bean
    RecoverDataframeImportsUseCase recoverDataframeImportsUseCase(
            DataframeImportAdmissionService admission,
            @Qualifier("processNextDataframeImportUseCase")
            ProcessNextDataframeImportUseCase processor) {
        return new DataframeImportRecoveryService(admission, processor);
    }

    @Bean
    ValidateDataframeImportUseCase validateDataframeImportUseCase(
            DataframeImportRecognizer recognizer,
            DataframeImportRowMapper mapper,
            DelimitedRecordReader reader,
            ImportWorkspaceLimits limits) {
        return new DataframeImportValidationService(recognizer, mapper, reader, limits);
    }

    @Bean
    QueryDataframeImportStatusUseCase queryDataframeImportStatusUseCase(
            LazyServiceStorage serviceStorage,
            DataframeImportRuntimeState state,
            Clock clock) {
        return new DataframeImportStatusService(new JdbcImportStatusReader(
                serviceStorage.dataSource(), clock, state::recoveryComplete));
    }

    @Bean
    ReplayDataframeImportUseCase replayDataframeImportUseCase(
            ImportDeliveryLedger ledger,
            DataframeImportAdmissionService admission,
            Clock clock) {
        return new DataframeImportReplayService(ledger, admission, clock);
    }

    @Bean
    RunDataframeImportRetentionUseCase runDataframeImportRetentionUseCase(
            ImportDeliveryLedger ledger,
            LocalImportTerminalStore terminals,
            ManagedImportSourceLifecycle sources,
            ImportWorkspace workspace,
            ImportCommitEvidenceStore commits,
            IocProperties properties,
            Clock clock) {
        return new DataframeImportRetentionService(
                ledger, terminals, sources, workspace, commits, clock,
                importRetentionTargets(properties.dataframeImport().runtime().retention()));
    }

    private List<ImportTerminalRetentionTarget> importRetentionTargets(
            IocProperties.DataframeImport.Retention retention) {
        return List.of(
                importRetentionTarget("successful", Set.of(ImportTerminalOutcome.SUCCEEDED),
                        retention.successful()),
                importRetentionTarget("unsuccessful", Set.of(
                                ImportTerminalOutcome.COMPLETED_WITH_ERRORS,
                                ImportTerminalOutcome.REJECTED),
                        retention.unsuccessful()));
    }

    private ImportTerminalRetentionTarget importRetentionTarget(
            String name,
            Set<ImportTerminalOutcome> outcomes,
            IocProperties.DataframeImport.Retention.Target target) {
        RetentionAction action = target.action() == RetentionActionType.ARCHIVE
                ? RetentionAction.ARCHIVE : RetentionAction.DELETE;
        Path archiveDirectory = target.archiveDir() == null ? null : Path.of(target.archiveDir());
        return new ImportTerminalRetentionTarget(
                name, outcomes, target.maxAge(), target.maxCount(), action, archiveDirectory);
    }

    @Bean
    DataframeImportRuntimeState dataframeImportRuntimeState() {
        return new DataframeImportRuntimeState();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    KeyedSerialExecutor dataframeImportLanes(
            DataframeImportCatalog catalog,
            DataframeImportRuntimeState state,
            DiagnosticSink diagnostics,
            Clock clock) {
        int workers = Math.max(2, catalog.sources().size() + 1);
        return new BoundedKeyedSerialExecutor(
                Executors.newFixedThreadPool(workers, runnable ->
                        Thread.ofPlatform().name("dataframe-import-worker").unstarted(runnable)),
                1,
                new DataframeImportLaneObserver(state, diagnostics, clock));
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    DataframeImportDetectionCoordinator dataframeImportDetectionCoordinator(
            DataframeImportCatalog catalog,
            DataframeImportAdmissionService admission,
            ManagedImportSourceLifecycle sources,
            KeyedSerialExecutor dataframeImportLanes,
            Clock clock) {
        var detector = new DataframeImportDetectionService(
                sources, admission, clock,
                () -> new ImportDeliveryId(UUID.randomUUID().toString()));
        List<ImportSourceId> sourceIds = catalog.sources().keySet().stream().sorted(
                java.util.Comparator.comparing(ImportSourceId::value)).toList();
        return new DataframeImportDetectionCoordinator(sourceIds, detector, dataframeImportLanes);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    DataframeImportDrainCoordinator dataframeImportDrainCoordinator(
            @Qualifier("processNextDataframeImportUseCase")
            ProcessNextDataframeImportUseCase processor,
            KeyedSerialExecutor dataframeImportLanes,
            IocProperties properties) {
        return new DataframeImportDrainCoordinator(
                processor, dataframeImportLanes,
                properties.dataframeImport().runtime().limits().recoveryBatchSize());
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    DataframeImportRecoveryCoordinator dataframeImportRecoveryCoordinator(
            @Qualifier("recoverDataframeImportsUseCase") RecoverDataframeImportsUseCase recovery,
            KeyedSerialExecutor dataframeImportLanes,
            IocProperties properties) {
        return new DataframeImportRecoveryCoordinator(
                recovery,
                dataframeImportLanes,
                properties.dataframeImport().runtime().limits().recoveryBatchSize());
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    DataframeImportSnapshotPinnedListener dataframeImportSnapshotPinnedListener(
            DataframeImportDrainCoordinator drain) {
        return new DataframeImportSnapshotPinnedListener(drain);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    ManagedDataframeImportRuntime managedDataframeImportRuntime(
            @Qualifier("recoverDataframeImportsUseCase") RecoverDataframeImportsUseCase recovery,
            DataframeImportDetectionCoordinator detection,
            DataframeImportDrainCoordinator drain,
            DataframeImportRecoveryCoordinator recoveryCoordinator,
            RunDataframeImportRetentionUseCase retention,
            ManagedImportSourceAdapters adapters,
            KeyedSerialExecutor dataframeImportLanes,
            DataframeImportRuntimeState state,
            DiagnosticSink diagnostics,
            IocProperties properties,
            Clock clock) {
        var runtime = properties.dataframeImport().runtime();
        return new ManagedDataframeImportRuntime(
                recovery, detection, drain, recoveryCoordinator, retention, adapters.changeSignals(),
                dataframeImportLanes, state, diagnostics, clock,
                runtime.limits().recoveryBatchSize(), runtime.retention().batchSize(),
                runtime.detect().reconcileInterval(), runtime.retention().interval(),
                runtime.shutdownTimeout());
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    DataframeImportHealthIndicator dataframeImportHealthIndicator(
            QueryDataframeImportStatusUseCase status,
            DataframeImportRuntimeState state,
            KeyedSerialExecutor dataframeImportLanes) {
        return new DataframeImportHealthIndicator(status, state, dataframeImportLanes);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon'")
    DataframeImportMetrics dataframeImportMetrics(
            MeterRegistry registry,
            DataframeImportRuntimeState state,
            KeyedSerialExecutor dataframeImportLanes) {
        return new DataframeImportMetrics(registry, state, dataframeImportLanes);
    }
}
