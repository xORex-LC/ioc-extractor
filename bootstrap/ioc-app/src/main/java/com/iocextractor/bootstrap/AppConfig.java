package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.maintenance.FileSystemRetentionStore;
import com.iocextractor.adapter.out.regex.JdkRegexPatternEngine;
import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.adapter.out.sink.csv.ArtifactFilter;
import com.iocextractor.adapter.out.sink.csv.ColumnSpec;
import com.iocextractor.adapter.out.sink.csv.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactSliceWriter;
import com.iocextractor.adapter.out.sink.csv.FileSystemSliceRetentionStore;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.adapter.out.sink.csv.NioExportOperationGuard;
import com.iocextractor.adapter.out.sink.csv.RowMapper;
import com.iocextractor.adapter.out.sink.csv.Transform;
import com.iocextractor.adapter.out.sink.csv.ValueProvider;
import com.iocextractor.adapter.out.source.TikaSourceReader;
import com.iocextractor.adapter.out.manifest.json.JacksonSliceManifestCodec;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactIdentityStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactProjectionWorkStore;
import com.iocextractor.adapter.out.store.jdbc.ArtifactIdAllocatorDefinition;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactRevisionReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalLifecycleWriter;
import com.iocextractor.adapter.out.store.jdbc.JdbcConfirmationReceiptStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcExpiredArtifactStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleClock;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleControlStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleActivationStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleHistoryStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleReconciliationStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleStatusReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcIngestionLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcExportProgressStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcExportRunLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcRunLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcStorageHealthProbe;
import com.iocextractor.adapter.out.store.jdbc.LegacyLedgerImporter;
import com.iocextractor.adapter.out.store.jdbc.DataframeArtifactSchema;
import com.iocextractor.adapter.out.store.jdbc.DataframeColumn;
import com.iocextractor.adapter.out.store.jdbc.DataframeFormatMigrations;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaPlan;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactIdBaseline;
import com.iocextractor.adapter.out.store.jdbc.SchemaMigrationResult;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.adapter.in.ingest.IngestionLifecycleState;
import com.iocextractor.adapter.in.ingest.FileSourceHasher;
import com.iocextractor.application.artifact.IngestRunRecoveryService;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.NoopArtifactProjection;
import com.iocextractor.application.artifact.NoopRunLedger;
import com.iocextractor.application.artifact.StoredArtifactIdentity;
import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionConvergenceService;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptReplayService;
import com.iocextractor.application.artifact.lifecycle.EventPublishingCanonicalArtifactWriter;
import com.iocextractor.application.artifact.lifecycle.ExistingRecordsActivationPolicy;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationService;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionService;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleHistoryRetentionService;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconciliationService;
import com.iocextractor.application.cadence.CadenceSource;
import com.iocextractor.application.cadence.CadenceSources;
import com.iocextractor.application.export.ExportChangeDetector;
import com.iocextractor.application.export.ExportRunRecoveryService;
import com.iocextractor.application.export.ExportService;
import com.iocextractor.application.export.SliceRetentionService;
import com.iocextractor.application.export.StandaloneSliceRetentionGuard;
import com.iocextractor.application.ingest.IngestionService;
import com.iocextractor.application.ingest.IngestionLifecycleSupport;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.maintenance.RetentionService;
import com.iocextractor.application.maintenance.RetentionTarget;
import com.iocextractor.application.port.in.maintenance.RunRetentionUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ConvergeArtifactProjectionsUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.PrepareLifecycleAdmissionUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ReconcileExpiredRecordsUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ResumeLifecycleActivationUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.ReplayConfirmationReceiptUseCase;
import com.iocextractor.application.port.in.artifact.lifecycle.RunLifecycleHistoryRetentionUseCase;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.in.export.RecoverExportUseCase;
import com.iocextractor.application.port.in.export.RunSliceRetentionUseCase;
import com.iocextractor.application.port.in.export.ValidateExportProfileUseCase;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.maintenance.RetentionStore;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityStore;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalObservationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleActivationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleReconciliationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleStatusReader;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.port.out.ingest.SourcePreparerFactory;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.ExportObserver;
import com.iocextractor.application.port.out.export.ExportOperationGuard;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.application.port.out.export.ExportRunReader;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.port.out.export.SliceRetentionStore;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.render.DiagnosticRenderer;
import com.iocextractor.diagnostics.render.TemplateDiagnosticRenderer;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.domain.attribute.MarkerSourceAttributor;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.adapter.out.psl.PslHostClassifier;
import com.iocextractor.domain.classify.FeaturePredicate;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.classify.MatchRule;
import com.iocextractor.domain.classify.RuleBasedMatchPolicy;
import com.iocextractor.domain.feature.DefaultIndicatorFeatureExtractor;
import com.iocextractor.domain.feature.DefaultIndicatorNormalizer;
import com.iocextractor.domain.feature.HostClassifier;
import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.feature.IndicatorNormalizer;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RegexIndicatorExtractor;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangRule;
import com.iocextractor.domain.refang.ReplacementRefanger;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.observability.diagnostics.LoggingDiagnosticSink;
import com.iocextractor.observability.diagnostics.RedactingDiagnosticContextFormatter;
import com.iocextractor.observability.diagnostics.ResilientDiagnosticSink;
import com.iocextractor.observability.logging.LoggingPipelineObserver;
import com.iocextractor.platform.concurrent.KeyedExecutionGuard;
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.ControlEventObserver;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.Lifecycle;
import org.springframework.integration.dsl.IntegrationFlow;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Composition root: the single place where the framework wires the otherwise
 * framework-free domain and application core to concrete adapters. Changing an
 * implementation (engine, reader, sink) is a change here, nowhere else.
 */
@Configuration
public class AppConfig {

    /** Extraction priority: hashes and URLs/IPs claim spans before bare domains. */
    private static final List<IndicatorType> PRIORITY = List.of(
            IndicatorType.SHA256, IndicatorType.SHA1, IndicatorType.MD5,
            IndicatorType.URL, IndicatorType.IPV4, IndicatorType.DOMAIN);

    @Bean
    public PatternEngine patternEngine(IocProperties props) {
        return props.engine() == EngineType.JDK
                ? new JdkRegexPatternEngine()
                : new Re2jPatternEngine();
    }

    @Bean
    public Refanger refanger(IocProperties props) {
        List<RefangRule> rules = props.refang().rules().stream()
                .map(r -> new RefangRule(r.from(), r.to()))
                .toList();
        return new ReplacementRefanger(rules);
    }

    @Bean
    public IndicatorNormalizer indicatorNormalizer() {
        return new DefaultIndicatorNormalizer();
    }

    @Bean
    public HostClassifier hostClassifier() {
        return new PslHostClassifier();
    }

    @Bean
    public IndicatorFeatureExtractor indicatorFeatureExtractor(IndicatorNormalizer normalizer,
                                                               HostClassifier hostClassifier) {
        return new DefaultIndicatorFeatureExtractor(normalizer, hostClassifier);
    }

    @Bean
    public MatchPolicy matchPolicy(IocProperties props, IndicatorFeatureExtractor featureExtractor) {
        Map<String, FeaturePredicate> registry = ConfigRegistryCatalog.featurePredicates();
        List<MatchRule> rules = props.classify().rules().stream()
                .map(rule -> new MatchRule(
                        rule.when(),
                        resolvePredicates(rule.when(), registry),
                        new MaskMatch(blankToNull(rule.urlMatch()), blankToNull(rule.hostMatch()))))
                .toList();
        return new RuleBasedMatchPolicy(featureExtractor, rules);
    }

    private List<FeaturePredicate> resolvePredicates(List<String> keys, Map<String, FeaturePredicate> registry) {
        List<FeaturePredicate> predicates = new ArrayList<>();
        for (String key : keys) {
            FeaturePredicate predicate = registry.get(key);
            if (predicate == null) {
                throw new IocExtractorException("Unknown classify predicate: " + key);
            }
            predicates.add(predicate);
        }
        return predicates;
    }

    @Bean
    public IndicatorExtractor indicatorExtractor(PatternEngine engine, IocProperties props) {
        Map<IndicatorType, String> ordered = new LinkedHashMap<>();
        for (IndicatorType type : PRIORITY) {
            String regex = props.patterns().get(type);
            if (regex != null) {
                ordered.put(type, regex);
            }
        }
        return new RegexIndicatorExtractor(engine, ordered);
    }

    @Bean
    public SourceAttributor sourceAttributor(PatternEngine engine, IocProperties props) {
        return new MarkerSourceAttributor(engine, props.source().sectionMarkers());
    }

    @Bean
    public SourceReader sourceReader(IocProperties props, Clock clock) {
        return new TikaSourceReader(sourceCharset(props), new DiagnosticFactory(clock));
    }

    @Bean
    public ArtifactIdBaseline artifactIdBaseline(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return new JdbcArtifactIdBaseline(dataframeStorageDataSource, dataframeSchemas(props));
    }

    @Bean
    @Primary
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DiagnosticRenderer diagnosticRenderer() {
        return new TemplateDiagnosticRenderer(new RedactingDiagnosticContextFormatter());
    }

    @Bean
    public DiagnosticSink diagnosticSink(DiagnosticRenderer renderer) {
        var logging = new LoggingDiagnosticSink(LoggerFactory.getLogger(LoggingDiagnosticSink.class), renderer);
        return new ResilientDiagnosticSink(logging, LoggerFactory.getLogger(ResilientDiagnosticSink.class));
    }

    @Bean
    public PipelineDecisionTracer pipelineDecisionTracer(IocProperties props) {
        return new LoggingPipelineDecisionTracer(
                LoggerFactory.getLogger(LoggingPipelineDecisionTracer.class),
                props.observability().perItemTraceEnabled());
    }

    @Bean
    public ExportPlanCatalog exportPlanCatalog(IocProperties props,
                                               DiagnosticSink diagnosticSink,
                                               Clock clock) {
        return new ExportPlanCatalog(props, diagnosticSink, new DiagnosticFactory(clock));
    }

    @Bean
    public ValidateExportProfileUseCase validateExportProfileUseCase(ExportPlanCatalog plans) {
        return command -> plans.requireProfile(command.profile());
    }

    @Bean
    public ExportObserver exportObserver() {
        return new LoggingExportObserver();
    }

    @Bean
    public IocExtractionServiceFactory iocExtractionServiceFactory(SourceReader reader,
                                                                   Refanger refanger,
                                                                   IndicatorExtractor extractor,
                                                                   SourceAttributor attributor,
                                                                   MatchPolicy matchPolicy,
                                                                   DiagnosticSink diagnosticSink,
                                                                   PipelineDecisionTracer decisionTracer,
                                                                   JdbcCanonicalArtifactRepository repository,
                                                                   CanonicalArtifactWriter canonicalArtifactWriter,
                                                                   ArtifactIdentityResolver artifactIdentityResolver,
                                                                   IocProperties props) {
        return new IocExtractionServiceFactory(reader, refanger, extractor, attributor, matchPolicy,
                props.pipeline().deduplicate(), props.observability().mode().token(),
                new LoggingPipelineObserver(), diagnosticSink,
                props.pipeline().failurePolicy().toPolicy(), props.pipeline().maxDiagnosticsPerRun(),
                repository, canonicalArtifactWriter, artifactIdentityResolver, decisionTracer);
    }

    @Bean
    public ProcessingPolicyIdentity processingPolicyIdentity(IocProperties props) {
        return new ProcessingPolicyIdentity(ProcessingPolicyFingerprint.from(props));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ioc.runtime",
            name = "mode",
            havingValue = RuntimeMode.ONESHOT_VALUE,
            matchIfMissing = true)
    public ExtractIocsUseCase extractIocsUseCase(IocExtractionServiceFactory factory,
                                                 ArtifactIdBaseline artifactIdBaseline,
                                                 CsvArtifactProjection csvArtifactProjection,
                                                 PipelineDecisionTracer decisionTracer,
                                                 PrepareLifecycleAdmissionUseCase lifecycleAdmission,
                                                 CanonicalObservationStore canonicalObservationStore,
                                                 ProcessingPolicyIdentity processingPolicyIdentity,
                                                 JdbcLifecycleClock lifecycleClock,
                                                 Clock clock,
                                                 IocProperties props) {
        List<ArtifactPreparer> preparers = artifactPreparers(
                artifactDefinitions(props, artifactIdBaseline), null, clock, decisionTracer);
        ExtractIocsUseCase delegate = factory.create(preparers, csvArtifactProjection);
        return command -> {
            lifecycleAdmission.prepare();
            if (props.lifecycle().validity().mode() != LifecycleValidityMode.FIXED || command.dryRun()) {
                return delegate.extract(command);
            }
            var observationId = new ObservationId(command.runId());
            var sourceKey = new FileSourceHasher().sha256(command.source());
            var receipt = new ConfirmationReceiptContext(
                    new ConfirmationReceiptId("receipt:" + observationId.value()),
                    processingPolicyIdentity.value(),
                    preparers.size(),
                    props.lifecycle().receiptRetention());
            try {
                var result = delegate.extract(new com.iocextractor.application.port.in.ExtractionCommand(
                        command.runId(), command.source(), false,
                        new LifecycleWriteContext(observationId, sourceKey.value(), receipt)));
                canonicalObservationStore.markTerminal(
                        observationId, lifecycleClock.now(), props.lifecycle().receiptRetention());
                return result;
            } catch (RuntimeException failure) {
                try {
                    canonicalObservationStore.markTerminal(
                            observationId, lifecycleClock.now(), props.lifecycle().receiptRetention());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public SourcePreparerFactory sourcePreparerFactory(IocProperties props,
                                                       ArtifactIdBaseline artifactIdBaseline,
                                                       PipelineDecisionTracer decisionTracer,
                                                       Clock clock) {
        var artifacts = artifactDefinitions(props, artifactIdBaseline);
        Map<String, ArtifactIdSequence> ids = new LinkedHashMap<>();
        for (CsvArtifactDefinition artifact : artifacts) {
            ids.put(artifact.name(), new ArtifactIdSequence(artifact.idStrategy(), artifact.idStart()));
        }
        return source -> new com.iocextractor.application.ingest.SourcePreparers(artifacts.stream()
                .map(artifact -> new CsvArtifactPreparer(
                        artifact, ids.get(artifact.name()), new DiagnosticFactory(clock),
                        source.key().value(), decisionTracer))
                .map(ArtifactPreparer.class::cast)
                .toList());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnServiceStorage
    public LazyServiceStorage lazyServiceStorage(IocProperties props,
                                                DiagnosticSink diagnosticSink,
                                                Clock clock) {
        return new LazyServiceStorage(props.storage().service(), diagnosticSink, clock);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public HikariDataSource serviceStorageDataSource(LazyServiceStorage storage) {
        return storage.dataSource();
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public SchemaMigrationResult serviceSchemaMigration(LazyServiceStorage storage) {
        return storage.migration();
    }

    @Bean
    @ConditionalOnJdbcLedger
    public IngestionLedger jdbcIngestionLedger(@Qualifier("serviceStorageDataSource")
                                               HikariDataSource serviceStorageDataSource,
                                               @Qualifier("serviceSchemaMigration")
                                               SchemaMigrationResult serviceSchemaMigration,
                                               Clock clock) {
        return new JdbcIngestionLedger(serviceStorageDataSource, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public JdbcStorageHealthProbe serviceStorageHealthProbe(@Qualifier("serviceStorageDataSource")
                                                            HikariDataSource serviceStorageDataSource,
                                                            @Qualifier("serviceSchemaMigration")
                                                            SchemaMigrationResult serviceSchemaMigration) {
        return new JdbcStorageHealthProbe(serviceStorageDataSource, "service");
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public RunLedger runLedger(@Qualifier("serviceStorageDataSource")
                               HikariDataSource serviceStorageDataSource,
                               @Qualifier("serviceSchemaMigration")
                               SchemaMigrationResult serviceSchemaMigration,
                               Clock clock) {
        return new JdbcRunLedger(serviceStorageDataSource, clock);
    }

    @Bean
    @ConditionalOnJdbcLedger
    public LegacyLedgerImporter legacyLedgerImporter(IocProperties props,
                                                     IngestionLedger ledger,
                                                     @Qualifier("serviceStorageDataSource")
                                                     HikariDataSource serviceStorageDataSource,
                                                     DiagnosticSink diagnosticSink,
                                                     Clock clock) {
        return new LegacyLedgerImporter(
                Path.of(props.ingestion().ledger().path()),
                ledger,
                serviceStorageDataSource,
                diagnosticSink,
                new DiagnosticFactory(clock),
                clock);
    }

    /**
     * Runs the legacy import during singleton instantiation, i.e. BEFORE Spring
     * Integration's pollers start (SmartLifecycle starts only after the context is
     * fully instantiated). This guarantees the legacy ledger is replayed before the
     * explicit ingestion startup coordinator runs source recovery and opens intake.
     * The returned summary bean is just the instantiation marker.
     */
    @Bean
    @ConditionalOnJdbcLedger
    public LegacyLedgerImporter.LegacyLedgerImportSummary legacyLedgerImport(LegacyLedgerImporter importer) {
        return importer.importAll();
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataframeStorageDataSource(IocProperties props) {
        IocProperties.Storage.Dataframe dataframe = props.storage().dataframe();
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                "dataframe",
                dataframe.url(),
                dataframe.sqlite().tuning(),
                dataframe.pool().writeMax(),
                dataframe.pool().readMax()));
    }

    @Bean
    public SchemaMigrationResult dataframeFormatSchemaMigration(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DiagnosticSink diagnosticSink,
            Clock clock) {
        return new SqliteUserVersionSchemaMigrator(
                dataframeStorageDataSource,
                DataframeFormatMigrations.sqlite(),
                diagnosticSink,
                new DiagnosticFactory(clock),
                "dataframe").migrate();
    }

    @Bean
    public DataframeSchemaPlan dataframeSchemaReconciliation(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            @Qualifier("dataframeFormatSchemaMigration")
            SchemaMigrationResult dataframeFormatSchemaMigration,
            IocProperties props,
            DiagnosticSink diagnosticSink,
            Clock clock) {
        return new DataframeSchemaReconciler(
                dataframeStorageDataSource, diagnosticSink, new DiagnosticFactory(clock), "dataframe")
                .reconcile(dataframeSchemas(props));
    }

    @Bean
    public ArtifactIdentityStore artifactIdentityStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DiagnosticSink diagnosticSink,
            Clock clock) {
        return new JdbcArtifactIdentityStore(
                dataframeStorageDataSource,
                clock,
                diagnosticSink,
                new DiagnosticFactory(clock),
                "dataframe");
    }

    @Bean
    public List<StoredArtifactIdentity> artifactIdentityValidation(
            ArtifactIdentityStore artifactIdentityStore,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return artifactIdentityStore.ensureAll(artifactIdentityDefinitions(props));
    }

    // ---- canonical record lifecycle ---------------------------------------

    @Bean
    public JdbcLifecycleClock lifecycleClock(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props,
            Clock clock) {
        return new JdbcLifecycleClock(
                dataframeStorageDataSource,
                clock,
                new LifecycleClockPolicy(
                        props.lifecycle().clock().maxBackwardSkew(),
                        props.lifecycle().clock().maxClampDuration()));
    }

    @Bean
    public LifecycleControlStore lifecycleControlStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return new JdbcLifecycleControlStore(dataframeStorageDataSource, dataframeSchemas(props));
    }

    @Bean
    public LifecycleActivationStore lifecycleActivationStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props,
            Clock clock) {
        return new JdbcLifecycleActivationStore(
                dataframeStorageDataSource, dataframeSchemas(props), clock);
    }

    @Bean
    public JdbcConfirmationReceiptStore confirmationReceiptStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return new JdbcConfirmationReceiptStore(
                dataframeStorageDataSource,
                dataframeSchemas(props),
                props.lifecycle().receiptRetention());
    }

    @Bean
    public CanonicalArtifactWriter canonicalArtifactWriter(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            ArtifactIdBaseline artifactIdBaseline,
            JdbcLifecycleClock lifecycleClock,
            ControlEventPublisher controlEventPublisher,
            IocProperties props,
            Clock clock) {
        var writer = new JdbcCanonicalLifecycleWriter(
                dataframeStorageDataSource,
                dataframeSchemas(props),
                artifactIdAllocatorDefinitions(props, artifactIdBaseline),
                lifecycleClock,
                new FixedRecordValidityPolicy(props.lifecycle().validity().fixedTtl()),
                clock);
        return new EventPublishingCanonicalArtifactWriter(writer, controlEventPublisher);
    }

    @Bean
    public ReplayConfirmationReceiptUseCase replayConfirmationReceiptUseCase(
            ConfirmationReceiptStore confirmationReceiptStore,
            CanonicalArtifactWriter canonicalArtifactWriter,
            JdbcLifecycleClock lifecycleClock) {
        return new ConfirmationReceiptReplayService(
                confirmationReceiptStore, canonicalArtifactWriter, lifecycleClock);
    }

    @Bean
    public ExpiredArtifactStore expiredArtifactStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return new JdbcExpiredArtifactStore(dataframeStorageDataSource, dataframeSchemas(props));
    }

    @Bean
    public ArtifactProjectionWorkStore artifactProjectionWorkStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            Clock clock) {
        return new JdbcArtifactProjectionWorkStore(dataframeStorageDataSource, clock);
    }

    @Bean
    public LifecycleReconciliationStore lifecycleReconciliationStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation) {
        return new JdbcLifecycleReconciliationStore(dataframeStorageDataSource);
    }

    @Bean
    public LifecycleHistoryStore lifecycleHistoryStore(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return new JdbcLifecycleHistoryStore(dataframeStorageDataSource, dataframeSchemas(props));
    }

    @Bean
    public CanonicalDataAdmissionState canonicalDataAdmissionState() {
        return new CanonicalDataAdmissionState();
    }

    @Bean
    public LifecycleRuntimeObserver lifecycleRuntimeObserver(
            DiagnosticSink diagnosticSink,
            Clock clock) {
        return new LifecycleRuntimeObserver(diagnosticSink, new DiagnosticFactory(clock));
    }

    @Bean
    public ReconcileExpiredRecordsUseCase reconcileExpiredRecordsUseCase(
            ExpiredArtifactStore expiredArtifactStore,
            LifecycleReconciliationStore lifecycleReconciliationStore,
            JdbcLifecycleClock lifecycleClock,
            ControlEventPublisher controlEventPublisher,
            IocProperties props) {
        return new LifecycleReconciliationService(
                dataframeArtifactNames(props),
                expiredArtifactStore,
                lifecycleReconciliationStore,
                lifecycleClock,
                controlEventPublisher,
                props.lifecycle().reconcile().batchSize());
    }

    @Bean
    public ConvergeArtifactProjectionsUseCase convergeArtifactProjectionsUseCase(
            ArtifactProjectionWorkStore artifactProjectionWorkStore,
            CsvArtifactProjection csvArtifactProjection,
            IocProperties props) {
        return new ArtifactProjectionConvergenceService(
                dataframeArtifactNames(props), artifactProjectionWorkStore, csvArtifactProjection);
    }

    @Bean
    public RunLifecycleHistoryRetentionUseCase runLifecycleHistoryRetentionUseCase(
            LifecycleHistoryStore lifecycleHistoryStore,
            ConfirmationReceiptStore confirmationReceiptStore,
            JdbcLifecycleClock lifecycleClock,
            IocProperties props) {
        return new LifecycleHistoryRetentionService(
                dataframeArtifactNames(props),
                lifecycleHistoryStore,
                lifecycleClock,
                props.lifecycle().historyRetention(),
                props.lifecycle().reconcile().batchSize(),
                confirmationReceiptStore);
    }

    @Bean
    public ResumeLifecycleActivationUseCase resumeLifecycleActivationUseCase(
            LifecycleControlStore lifecycleControlStore,
            LifecycleActivationStore lifecycleActivationStore,
            ConvergeArtifactProjectionsUseCase convergeArtifactProjectionsUseCase,
            JdbcLifecycleClock lifecycleClock,
            IocProperties props) {
        IocProperties.Lifecycle.Validity validity = props.lifecycle().validity();
        var activationPolicy = new LifecycleActivationPolicy(
                validity.mode() == LifecycleValidityMode.FIXED,
                validity.mode() == LifecycleValidityMode.FIXED
                        ? "record-validity:fixed:v1" : "record-validity:disabled:v1",
                validity.existingRecords() == ExistingRecordsPolicy.EXPIRE
                        ? ExistingRecordsActivationPolicy.EXPIRE
                        : ExistingRecordsActivationPolicy.REJECT);
        return new LifecycleActivationService(
                dataframeArtifactNames(props),
                lifecycleControlStore,
                lifecycleActivationStore,
                convergeArtifactProjectionsUseCase,
                lifecycleClock,
                activationPolicy,
                props.lifecycle().reconcile().batchSize());
    }

    @Bean
    public PrepareLifecycleAdmissionUseCase prepareLifecycleAdmissionUseCase(
            LifecycleControlStore lifecycleControlStore,
            JdbcLifecycleClock lifecycleClock,
            ResumeLifecycleActivationUseCase resumeLifecycleActivationUseCase,
            ReconcileExpiredRecordsUseCase reconcileExpiredRecordsUseCase,
            ConvergeArtifactProjectionsUseCase convergeArtifactProjectionsUseCase,
            CanonicalDataAdmissionState canonicalDataAdmissionState,
            LifecycleRuntimeObserver observer) {
        LifecycleAdmissionService delegate = new LifecycleAdmissionService(
                lifecycleControlStore,
                lifecycleClock,
                resumeLifecycleActivationUseCase,
                reconcileExpiredRecordsUseCase,
                convergeArtifactProjectionsUseCase,
                canonicalDataAdmissionState);
        return () -> {
            try {
                var result = delegate.prepare();
                observer.admissionCompleted(result);
                return result;
            } catch (RuntimeException failure) {
                observer.admissionFailed(failure);
                throw failure;
            }
        };
    }

    @Bean
    public LifecycleStatusReader lifecycleStatusReader(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            JdbcLifecycleClock lifecycleClock,
            IocProperties props) {
        return new JdbcLifecycleStatusReader(
                dataframeStorageDataSource, dataframeSchemas(props), lifecycleClock);
    }

    @Bean
    @Primary
    public JdbcCanonicalArtifactRepository jdbcCanonicalArtifactRepository(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            @Qualifier("artifactIdentityValidation")
            List<StoredArtifactIdentity> artifactIdentityValidation,
            ArtifactIdentityResolver artifactIdentityResolver,
            IocProperties props,
            Clock clock,
            JdbcLifecycleClock lifecycleClock) {
        Objects.requireNonNull(artifactIdentityValidation, "artifactIdentityValidation");
        return new JdbcCanonicalArtifactRepository(
                dataframeStorageDataSource,
                dataframeSchemas(props),
                artifactIdentityResolver,
                clock,
                lifecycleClock);
    }

    @Bean
    public CsvArtifactProjection csvArtifactProjection(JdbcCanonicalArtifactRepository jdbcCanonicalArtifactRepository,
                                                       IocProperties props,
                                                       Clock clock) {
        return new CsvArtifactProjection(
                jdbcCanonicalArtifactRepository,
                artifactHeaders(props),
                canonicalArtifactPaths(props),
                writeFormat(props.sink().csv()),
                csvCharset(props),
                new DiagnosticFactory(clock));
    }

    // ---- immutable artifact export (resolved only by export command/scheduler) ----

    @Bean
    @Lazy
    public ArtifactRevisionReader artifactRevisionReader(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation) {
        return new JdbcArtifactRevisionReader(dataframeStorageDataSource);
    }

    @Bean
    @Lazy
    public SnapshotSliceReader snapshotSliceReader(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props,
            DiagnosticSink diagnosticSink,
            Clock clock,
            JdbcLifecycleClock lifecycleClock) {
        return new JdbcSnapshotSliceReader(
                dataframeStorageDataSource,
                dataframeSchemas(props),
                clock,
                lifecycleClock,
                diagnosticSink,
                new DiagnosticFactory(clock));
    }

    @Bean
    @Lazy
    @ConditionalOnServiceStorage
    public JdbcExportRunLedger exportRunLedger(
            LazyServiceStorage serviceStorage,
            DiagnosticSink diagnosticSink,
            Clock clock) {
        return new JdbcExportRunLedger(
                serviceStorage.dataSource(), clock, diagnosticSink, new DiagnosticFactory(clock));
    }

    @Bean
    @Lazy
    @ConditionalOnServiceStorage
    public ExportProgressStore exportProgressStore(LazyServiceStorage serviceStorage) {
        return new JdbcExportProgressStore(serviceStorage.dataSource());
    }

    @Bean
    @Lazy
    public SliceManifestCodec sliceManifestCodec() {
        return new JacksonSliceManifestCodec();
    }

    @Bean
    @Lazy
    public ArtifactSliceWriter artifactSliceWriter(IocProperties props,
                                                   SliceManifestCodec sliceManifestCodec,
                                                   DiagnosticSink diagnosticSink,
                                                   Clock clock) {
        return new CsvArtifactSliceWriter(
                Path.of(props.export().root()), sliceManifestCodec,
                diagnosticSink, new DiagnosticFactory(clock));
    }

    @Bean
    @Lazy
    public ExportOperationGuard exportOperationGuard(IocProperties props) {
        return new NioExportOperationGuard(Path.of(props.export().root()));
    }

    @Bean
    @Lazy
    @ConditionalOnServiceStorage
    public ExportRunRecoveryService exportRunRecoveryService(
            ExportRunLedger exportRunLedger,
            ExportProgressStore exportProgressStore,
            ArtifactSliceWriter artifactSliceWriter,
            ExportObserver exportObserver,
            DiagnosticSink diagnosticSink,
            ControlEventPublisher controlEventPublisher,
            Clock clock) {
        return new ExportRunRecoveryService(
                exportRunLedger, artifactSliceWriter, exportProgressStore,
                new ExportChangeDetector(), exportObserver,
                diagnosticSink, new DiagnosticFactory(clock), clock, controlEventPublisher);
    }

    @Bean
    @Lazy
    @ConditionalOnServiceStorage
    public ExportArtifactsUseCase exportArtifactsUseCase(
            ExportPlanCatalog plans,
            ArtifactRevisionReader artifactRevisionReader,
            ExportProgressStore exportProgressStore,
            ExportRunLedger exportRunLedger,
            SnapshotSliceReader snapshotSliceReader,
            ArtifactSliceWriter artifactSliceWriter,
            ExportRunRecoveryService exportRunRecoveryService,
            ExportOperationGuard exportOperationGuard,
            ExportObserver exportObserver,
            ControlEventPublisher controlEventPublisher,
            PrepareLifecycleAdmissionUseCase lifecycleAdmission,
            Clock clock) {
        ExportArtifactsUseCase delegate = new ExportService(
                plans.plans(), artifactRevisionReader, exportProgressStore, exportRunLedger,
                snapshotSliceReader, artifactSliceWriter,
                exportRunRecoveryService, exportOperationGuard, new ExportChangeDetector(),
                exportObserver, controlEventPublisher, clock, () -> java.util.UUID.randomUUID().toString());
        return command -> {
            lifecycleAdmission.prepare();
            return delegate.export(command);
        };
    }

    @Bean
    @Lazy
    @Primary
    @ConditionalOnServiceStorage
    public RecoverExportUseCase recoverExportUseCase(
            ExportRunRecoveryService recoveryService,
            ExportOperationGuard operationGuard) {
        return () -> {
            try (ExportOperationGuard.Lease ignored = operationGuard.acquire()) {
                return recoveryService.recoverIncomplete();
            }
        };
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public DaemonExportScheduler daemonExportScheduler(
            ExportPlanCatalog catalog,
            ArtifactRevisionReader artifactRevisionReader,
            ExportProgressStore exportProgressStore,
            RecoverExportUseCase recoverExportUseCase,
            ExportArtifactsUseCase exportArtifactsUseCase,
            CanonicalDataAdmissionState canonicalDataAdmissionState,
            IocProperties props,
            Clock clock) {
        IocProperties.Export.Trigger trigger = props.export().trigger();
        Map<String, CadenceSource> cadences = new LinkedHashMap<>();
        for (var plan : catalog.plans()) {
            cadences.put(plan.profile().name(), CadenceSources.create(
                    trigger.type().token(), trigger.interval(), trigger.quietPeriod(), trigger.maxCap(), clock));
        }
        return new DaemonExportScheduler(
                catalog.plans(), cadences, artifactRevisionReader, exportProgressStore,
                recoverExportUseCase, exportArtifactsUseCase, cadencePollInterval(trigger),
                exportNudgePolicy(trigger), canonicalDataAdmissionState);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public CanonicalArtifactsChangedExportListener canonicalArtifactsChangedExportListener(
            ExportNudgeTrigger exportNudgeTrigger,
            ControlEventObserver observer) {
        return new CanonicalArtifactsChangedExportListener(exportNudgeTrigger, observer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public LifecycleDeadlineScheduler lifecycleDeadlineScheduler(
            CanonicalDataAdmissionState canonicalDataAdmissionState,
            ExpiredArtifactStore expiredArtifactStore,
            ReconcileExpiredRecordsUseCase reconcileExpiredRecordsUseCase,
            RunLifecycleHistoryRetentionUseCase runLifecycleHistoryRetentionUseCase,
            IocProperties props,
            Clock clock,
            LifecycleRuntimeObserver observer) {
        return new LifecycleDeadlineScheduler(
                canonicalDataAdmissionState,
                expiredArtifactStore,
                reconcileExpiredRecordsUseCase,
                runLifecycleHistoryRetentionUseCase,
                props.lifecycle().reconcile().backstopInterval(),
                clock,
                observer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public LifecycleProjectionScheduler lifecycleProjectionScheduler(
            CanonicalDataAdmissionState canonicalDataAdmissionState,
            ConvergeArtifactProjectionsUseCase convergeArtifactProjectionsUseCase,
            IocProperties props,
            LifecycleRuntimeObserver observer) {
        return new LifecycleProjectionScheduler(
                canonicalDataAdmissionState,
                convergeArtifactProjectionsUseCase,
                props.lifecycle().reconcile().backstopInterval(),
                observer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public LifecycleControlEventListener lifecycleControlEventListener(
            LifecycleDeadlineScheduler lifecycleDeadlineScheduler,
            LifecycleProjectionScheduler lifecycleProjectionScheduler,
            ControlEventObserver observer) {
        return new LifecycleControlEventListener(
                lifecycleDeadlineScheduler, lifecycleProjectionScheduler, observer);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public ExportHealthIndicator exportHealthIndicator(
            ExportPlanCatalog catalog,
            ArtifactRevisionReader artifactRevisionReader,
            ExportProgressStore exportProgressStore,
            ExportRunReader exportRunReader,
            Clock clock) {
        return new ExportHealthIndicator(
                catalog.plans(), artifactRevisionReader, exportProgressStore, exportRunReader, clock);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true'")
    public SliceRetentionStore sliceRetentionStore(
            IocProperties props, SliceManifestCodec sliceManifestCodec) {
        return new FileSystemSliceRetentionStore(Path.of(props.export().root()), sliceManifestCodec);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true' && ("
            + "'${ioc.sync.enabled:false}' != 'true' || "
            + "'${ioc.sync.publish.enabled:false}' != 'true' || "
            + "'${ioc.storage.service.type:disabled}' != 'jdbc')")
    public SliceRetentionGuard sliceRetentionGuard() {
        return StandaloneSliceRetentionGuard.INSTANCE;
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true'")
    public RunSliceRetentionUseCase runSliceRetentionUseCase(
            SliceRetentionStore store,
            SliceRetentionGuard guard,
            ExportPlanCatalog catalog,
            IocProperties props,
            Clock clock) {
        IocProperties.Export.Retention retention = props.export().retention();
        List<String> profiles = catalog.plans().stream()
                .map(plan -> plan.profile().name())
                .toList();
        return new SliceRetentionService(
                store, guard, profiles, retention.maxAge(), retention.maxCount(), clock);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.export.enabled:true}' == 'true'")
    public DaemonSliceRetentionScheduler daemonSliceRetentionScheduler(
            RunSliceRetentionUseCase useCase,
            IocProperties props) {
        IocProperties.Maintenance.Retention maintenance = props.maintenance() == null
                ? null : props.maintenance().retention();
        Duration interval = maintenance == null || maintenance.interval() == null
                ? Duration.ofHours(1) : maintenance.interval();
        Duration initialDelay = maintenance == null || maintenance.initialDelay() == null
                ? Duration.ofMinutes(5) : maintenance.initialDelay();
        return new DaemonSliceRetentionScheduler(useCase, interval, initialDelay);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public IngestRunRecoveryService ingestRunRecoveryService(
            RunLedger runLedger,
            ArtifactProjection csvArtifactProjection,
            DiagnosticSink diagnosticSink) {
        return new IngestRunRecoveryService(runLedger, csvArtifactProjection, diagnosticSink);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public KeyedExecutionGuard ingestionExecutionGuard() {
        return new SynchronousKeyedExecutionGuard();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public IngestionLifecycleHealthIndicator ingestionLifecycleHealthIndicator(
            IngestionLifecycleState lifecycleState,
            KeyedExecutionGuard ingestionExecutionGuard,
            @Qualifier("iocIngestionFlow") IntegrationFlow intakeFlow) {
        if (!(intakeFlow instanceof Lifecycle lifecycle)) {
            throw new IllegalStateException("iocIngestionFlow does not expose lifecycle state");
        }
        return new IngestionLifecycleHealthIndicator(lifecycleState, lifecycle, ingestionExecutionGuard);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public IngestionService ingestionService(IngestionLedger ledger,
                                             SourceLifecycle sourceLifecycle,
                                             SourcePreparerFactory sourcePreparerFactory,
                                             IocExtractionServiceFactory extractionFactory,
                                             ObjectProvider<RunLedger> runLedger,
                                             ObjectProvider<ArtifactProjection> projection,
                                             ReplayConfirmationReceiptUseCase receiptReplay,
                                             CanonicalObservationStore observations,
                                             JdbcLifecycleClock lifecycleClock,
                                             ProcessingPolicyIdentity processingPolicyIdentity,
                                             ControlEventPublisher controlEventPublisher,
                                             DiagnosticSink diagnosticSink,
                                             KeyedExecutionGuard ingestionExecutionGuard,
                                             IocProperties props,
                                             Clock clock) {
        IngestionLifecycleSupport lifecycleSupport = props.lifecycle().validity().mode()
                == LifecycleValidityMode.FIXED
                ? new IngestionLifecycleSupport(
                        receiptReplay,
                        observations,
                        lifecycleClock,
                        processingPolicyIdentity.value(),
                        props.lifecycle().receiptRetention())
                : null;
        return new IngestionService(
                ledger,
                sourceLifecycle,
                sourcePreparerFactory,
                extractionFactory,
                runLedger.getIfAvailable(NoopRunLedger::new),
                projection.getIfAvailable(() -> NoopArtifactProjection.INSTANCE),
                controlEventPublisher,
                clock,
                diagnosticSink,
                ingestionExecutionGuard,
                lifecycleSupport);
    }

    @Bean
    public ArtifactIdentityResolver artifactIdentityResolver(IocProperties props) {
        return new CanonicalArtifactIdentityResolver(artifactIdentityDefinitions(props));
    }

    @Bean
    @ConditionalOnFileLedger
    public IngestionLedgerHealthIndicator ingestionLedgerHealthIndicator(IocProperties props) {
        return new IngestionLedgerHealthIndicator(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public JdbcStorageHealthIndicator jdbcStorageHealthIndicator(JdbcStorageHealthProbe serviceStorageHealthProbe) {
        return new JdbcStorageHealthIndicator(serviceStorageHealthProbe);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public JdbcStorageHealthIndicator dataframeStorageHealthIndicator(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            @Qualifier("dataframeFormatSchemaMigration") SchemaMigrationResult dataframeFormatSchemaMigration) {
        return new JdbcStorageHealthIndicator(new JdbcStorageHealthProbe(dataframeStorageDataSource, "dataframe"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public LifecycleHealthIndicator lifecycleHealthIndicator(
            LifecycleStatusReader lifecycleStatusReader,
            CanonicalDataAdmissionState canonicalDataAdmissionState,
            IocProperties props) {
        return new LifecycleHealthIndicator(
                lifecycleStatusReader,
                canonicalDataAdmissionState,
                props.lifecycle().reconcile().backstopInterval());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = RuntimeMode.DAEMON_VALUE)
    public ArtifactStorageHealthIndicator artifactStorageHealthIndicator(IocProperties props) {
        return new ArtifactStorageHealthIndicator(props);
    }

    // ---- daemon housekeeping: retention reaper -----------------------------

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.maintenance.retention.enabled:false}' == 'true'")
    public RetentionStore retentionStore() {
        return new FileSystemRetentionStore();
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.maintenance.retention.enabled:false}' == 'true'")
    public RunRetentionUseCase runRetentionUseCase(RetentionStore store,
                                                   IocProperties props,
                                                   Clock clock) {
        return new RetentionService(
                store,
                retentionTargets(props),
                clock);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.maintenance.retention.enabled:false}' == 'true'")
    public DaemonMaintenanceScheduler daemonMaintenanceScheduler(RunRetentionUseCase useCase,
                                                                 IocProperties props) {
        IocProperties.Maintenance.Retention retention = props.maintenance().retention();
        Duration interval = retention.interval() == null ? Duration.ofHours(1) : retention.interval();
        Duration initialDelay = retention.initialDelay() == null
                ? Duration.ofMinutes(5) : retention.initialDelay();
        return new DaemonMaintenanceScheduler(useCase, interval, initialDelay);
    }

    private List<RetentionTarget> retentionTargets(IocProperties props) {
        IocProperties.Maintenance maintenance = props.maintenance();
        if (maintenance == null || maintenance.retention() == null
                || maintenance.retention().targets() == null) {
            return List.of();
        }
        List<RetentionTarget> targets = new ArrayList<>();
        for (IocProperties.Maintenance.Retention.Target target : maintenance.retention().targets()) {
            RetentionAction action = target.action() == RetentionActionType.ARCHIVE
                    ? RetentionAction.ARCHIVE
                    : RetentionAction.DELETE;
            Path archiveDir = (target.archiveDir() == null || target.archiveDir().isBlank())
                    ? null : Path.of(target.archiveDir());
            targets.add(new RetentionTarget(
                    target.name(),
                    Path.of(target.dir()),
                    target.maxAge(),
                    target.maxCount(),
                    action,
                    archiveDir));
        }
        return targets;
    }

    private Duration cadencePollInterval(IocProperties.Export.Trigger trigger) {
        if (!trigger.type().isQuietPeriod()) {
            return trigger.interval();
        }
        return trigger.quietPeriod().compareTo(trigger.maxCap()) <= 0
                ? trigger.quietPeriod() : trigger.maxCap();
    }

    private ExportNudgePolicy exportNudgePolicy(IocProperties.Export.Trigger trigger) {
        if (!trigger.type().isQuietPeriod()) {
            return ExportNudgePolicy.disabled();
        }
        return new ExportNudgePolicy(true, trigger.quietPeriod());
    }

    // ---- artifact assembly -------------------------------------------------

    private List<ArtifactPreparer> artifactPreparers(List<CsvArtifactDefinition> artifacts,
                                                     String sourceKey,
                                                     Clock clock,
                                                     PipelineDecisionTracer decisionTracer) {
        return artifacts.stream()
                .map(artifact -> new CsvArtifactPreparer(
                        artifact,
                        new ArtifactIdSequence(artifact.idStrategy(), artifact.idStart()),
                        new DiagnosticFactory(clock),
                        sourceKey,
                        decisionTracer))
                .map(ArtifactPreparer.class::cast)
                .toList();
    }

    private List<CsvArtifactDefinition> artifactDefinitions(IocProperties props,
                                                            ArtifactIdBaseline artifactIdBaseline) {
        Map<String, ValueProvider> providers = ConfigRegistryCatalog.valueProviders();
        Map<String, Transform> transforms = ConfigRegistryCatalog.transforms();
        Map<String, Predicate<ClassifiedIndicator>> filters = ConfigRegistryCatalog.artifactFilters();
        List<CsvArtifactDefinition> artifacts = new ArrayList<>();
        for (IocProperties.Sink.Artifact artifact : props.sink().artifacts()) {
            if (!artifact.enabled()) {
                continue;
            }
            RowMapper mapper = new ConfigurableRowMapper(columnSpecs(artifact), providers, transforms);
            artifacts.add(new CsvArtifactDefinition(
                    artifact.name(),
                    EnumSet.copyOf(artifact.accepts()),
                    artifactFilter(artifact, filters),
                    mapper,
                    strategyOf(artifact.id()),
                    startOf(artifact.name(), artifact, artifactIdBaseline)));
        }
        return artifacts;
    }

    private Map<String, Path> canonicalArtifactPaths(IocProperties props) {
        Map<String, Path> paths = new HashMap<>();
        for (IocProperties.Sink.Artifact artifact : props.sink().artifacts()) {
            if (artifact.enabled()) {
                paths.put(artifact.name(), Path.of(artifact.path()));
            }
        }
        return paths;
    }

    /**
     * Projection headers are the configured artifact column names — the same list
     * a {@code ConfigurableRowMapper} exposes — so the projection needs neither the
     * row mapper nor the lookup repository to know its output shape.
     */
    private Map<String, List<String>> artifactHeaders(IocProperties props) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (IocProperties.Sink.Artifact artifact : props.sink().artifacts()) {
            if (artifact.enabled()) {
                headers.put(artifact.name(),
                        artifact.columns().stream().map(column -> column.name()).toList());
            }
        }
        return headers;
    }

    private ArtifactFilter artifactFilter(IocProperties.Sink.Artifact artifact,
                                          Map<String, Predicate<ClassifiedIndicator>> filters) {
        return new ArtifactFilter(
                resolveArtifactPredicates(artifact.include(), filters),
                resolveArtifactPredicates(artifact.exclude(), filters));
    }

    private List<Predicate<ClassifiedIndicator>> resolveArtifactPredicates(
            List<String> keys,
            Map<String, Predicate<ClassifiedIndicator>> filters) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Predicate<ClassifiedIndicator>> predicates = new ArrayList<>();
        for (String key : keys) {
            Predicate<ClassifiedIndicator> predicate = filters.get(key);
            if (predicate == null) {
                throw new IocExtractorException("Unknown artifact filter predicate: " + key);
            }
            predicates.add(predicate);
        }
        return predicates;
    }

    private List<ColumnSpec> columnSpecs(IocProperties.Sink.Artifact artifact) {
        return artifact.columns().stream()
                .map(column -> new ColumnSpec(column.name(), column.from(),
                        column.value(), column.whenType(), column.transform()))
                .toList();
    }

    private List<DataframeArtifactSchema> dataframeSchemas(IocProperties props) {
        return props.sink().artifacts().stream()
                .filter(artifact -> artifact.enabled())
                .map(artifact -> new DataframeArtifactSchema(
                        artifact.name(),
                        artifact.columns().stream()
                                .map(column -> new DataframeColumn(column.name(), column.type()))
                                .toList()))
                .toList();
    }

    private List<String> dataframeArtifactNames(IocProperties props) {
        return dataframeSchemas(props).stream().map(DataframeArtifactSchema::artifactName).toList();
    }

    private List<ArtifactIdentityDefinition> artifactIdentityDefinitions(IocProperties props) {
        return props.artifactIdentity().artifacts().stream()
                .map(artifact -> new ArtifactIdentityDefinition(
                        artifact.name(),
                        artifact.keyColumns(),
                        artifact.keyMode() == ArtifactKeyMode.FIRST_NON_EMPTY,
                        artifact.epoch() == null ? 1 : artifact.epoch()))
                .toList();
    }

    private List<ArtifactIdAllocatorDefinition> artifactIdAllocatorDefinitions(
            IocProperties props,
            ArtifactIdBaseline artifactIdBaseline) {
        Map<String, Integer> epochs = artifactIdentityDefinitions(props).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ArtifactIdentityDefinition::artifactName,
                        ArtifactIdentityDefinition::epoch));
        return props.sink().artifacts().stream()
                .filter(IocProperties.Sink.Artifact::enabled)
                .filter(IocProperties.Sink.Artifact::hasPublicIdColumn)
                .map(artifact -> new ArtifactIdAllocatorDefinition(
                        artifact.name(),
                        strategyOf(artifact.id()),
                        startOf(artifact.name(), artifact, artifactIdBaseline),
                        epochs.getOrDefault(artifact.name(), 1)))
                .toList();
    }

    private com.iocextractor.application.artifact.ArtifactIdStrategy strategyOf(
            IocProperties.Sink.Artifact.Id id) {
        return id != null && id.strategy() == ArtifactIdStrategy.DESCENDING
                ? com.iocextractor.application.artifact.ArtifactIdStrategy.DESCENDING
                : com.iocextractor.application.artifact.ArtifactIdStrategy.ASCENDING;
    }

    /**
     * Resolve the starting id. {@code auto} continues the ascending sequence from
     * the named artifact's current max id (+1); a numeric value is used verbatim.
     */
    private long startOf(String artifactName,
                         IocProperties.Sink.Artifact artifact,
                         ArtifactIdBaseline artifactIdBaseline) {
        IocProperties.Sink.Artifact.Id id = artifact.id();
        if (!artifact.hasPublicIdColumn()) {
            return 0L;
        }
        if (id == null || id.start() == null) {
            return artifactIdBaseline.maxId(artifactName) + 1;
        }
        IdStart start = id.start();
        return switch (start) {
            case IdStart.Auto ignored -> artifactIdBaseline.maxId(artifactName) + 1;
            case IdStart.Explicit explicit -> explicit.value();
        };
    }

    /** A blank/absent mask code means "no match" -> rendered as the CSV null literal. */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Output charset for generated CSV projections and export slices. Blank or
     * absent {@code ioc.sink.csv.charset} means UTF-8.
     */
    private Charset csvCharset(IocProperties props) {
        return resolveCharset(props.sink().csv().charset(), StandardCharsets.UTF_8, "ioc.sink.csv.charset");
    }

    /**
     * Forced input charset (boundary 1). {@code auto}/blank/absent means Tika
     * auto-detection; an explicit name forces decoding of text/HTML sources.
     */
    private Charset sourceCharset(IocProperties props) {
        String value = props.source().charset();
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return resolveCharset(value, null, "ioc.source.charset");
    }

    private Charset resolveCharset(String value, Charset fallback, String key) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Charset.forName(value.trim());
        } catch (IllegalArgumentException e) {  // unsupported or illegal name
            throw new IocExtractorException("Unsupported charset for " + key + ": '" + value.trim()
                    + "'. Use a JVM-supported charset name (e.g. UTF-8, windows-1251).", e);
        }
    }

    private CSVFormat writeFormat(IocProperties.Sink.Csv csv) {
        return CSVFormat.Builder.create()
                .setDelimiter(csv.delimiter().charAt(0))
                .setQuote(csv.quote().charAt(0))
                .setNullString(csv.nullLiteral())
                .setQuoteMode(QuoteMode.ALL_NON_NULL)
                .setRecordSeparator("\r\n")
                .build();
    }
}
