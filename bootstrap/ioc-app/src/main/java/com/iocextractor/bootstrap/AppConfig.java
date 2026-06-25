package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.lookup.CsvArtifactLookupRepository;
import com.iocextractor.adapter.out.lookup.CsvMaskLookupRepository;
import com.iocextractor.adapter.out.maintenance.FileSystemRetentionStore;
import com.iocextractor.adapter.out.regex.JdkRegexPatternEngine;
import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.adapter.out.sink.csv.AddressIpValueProvider;
import com.iocextractor.adapter.out.sink.csv.AddressUrlValueProvider;
import com.iocextractor.adapter.out.sink.csv.ArtifactFilter;
import com.iocextractor.adapter.out.sink.csv.ColumnSpec;
import com.iocextractor.adapter.out.sink.csv.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactRepositories;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.CsvIocSink;
import com.iocextractor.adapter.out.sink.csv.CsvStableIdIndex;
import com.iocextractor.adapter.out.sink.csv.IdGenerator;
import com.iocextractor.adapter.out.sink.csv.IdValueProvider;
import com.iocextractor.adapter.out.sink.csv.IndicatorValueProvider;
import com.iocextractor.adapter.out.sink.csv.LowerHostTransform;
import com.iocextractor.adapter.out.sink.csv.LowercaseTransform;
import com.iocextractor.adapter.out.sink.csv.MatchHostValueProvider;
import com.iocextractor.adapter.out.sink.csv.MatchUrlValueProvider;
import com.iocextractor.adapter.out.sink.csv.PartitionedCsvSinkFactory;
import com.iocextractor.adapter.out.sink.csv.RowMapper;
import com.iocextractor.adapter.out.sink.csv.SourceLabelValueProvider;
import com.iocextractor.adapter.out.sink.csv.StripPrefixTransform;
import com.iocextractor.adapter.out.sink.csv.Transform;
import com.iocextractor.adapter.out.sink.csv.UppercaseTransform;
import com.iocextractor.adapter.out.sink.csv.ValueProvider;
import com.iocextractor.adapter.out.source.TikaSourceReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactIdentityStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcIngestionLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcIocSink;
import com.iocextractor.adapter.out.store.jdbc.JdbcLookupRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcRunLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcStorageHealthProbe;
import com.iocextractor.adapter.out.store.jdbc.LegacyLedgerImporter;
import com.iocextractor.adapter.out.store.jdbc.DataframeArtifactSchema;
import com.iocextractor.adapter.out.store.jdbc.DataframeColumn;
import com.iocextractor.adapter.out.store.jdbc.DataframeFormatMigrations;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaPlan;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler;
import com.iocextractor.adapter.out.store.jdbc.SchemaMigrationResult;
import com.iocextractor.adapter.out.store.jdbc.ServiceSchemaMigrations;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.application.aggregation.AggregationService;
import com.iocextractor.application.aggregation.AggregationRunRecoveryService;
import com.iocextractor.application.aggregation.ArtifactIdentityDefinition;
import com.iocextractor.application.aggregation.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.aggregation.KeepFirstMergePolicy;
import com.iocextractor.application.aggregation.NoopArtifactProjection;
import com.iocextractor.application.aggregation.NoopRunLedger;
import com.iocextractor.application.aggregation.StoredArtifactIdentity;
import com.iocextractor.application.maintenance.AggregatedPartitionRetentionEligibility;
import com.iocextractor.application.ingest.IngestionService;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.maintenance.RetentionService;
import com.iocextractor.application.maintenance.RetentionTarget;
import com.iocextractor.application.port.in.aggregation.AggregatePartitionsUseCase;
import com.iocextractor.application.port.in.maintenance.RunRetentionUseCase;
import com.iocextractor.application.port.out.aggregation.AggregationTrigger;
import com.iocextractor.application.port.out.aggregation.ArtifactProjection;
import com.iocextractor.application.port.out.maintenance.RetentionStore;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityStore;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.aggregation.PartitionArtifactRepository;
import com.iocextractor.application.port.out.aggregation.RunLedger;
import com.iocextractor.application.port.out.aggregation.StableIdIndex;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.PartitionSinkFactory;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
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
import com.iocextractor.domain.classify.FeaturePredicates;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.classify.MatchRule;
import com.iocextractor.domain.classify.RuleBasedMatchPolicy;
import com.iocextractor.domain.feature.DefaultIndicatorFeatureExtractor;
import com.iocextractor.domain.feature.DefaultIndicatorNormalizer;
import com.iocextractor.domain.feature.HostClassifier;
import com.iocextractor.domain.feature.IndicatorFeatureExtractor;
import com.iocextractor.domain.feature.IndicatorNormalizer;
import com.iocextractor.domain.feature.NetworkAddressClassifier;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RegexIndicatorExtractor;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangRule;
import com.iocextractor.domain.refang.ReplacementRefanger;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.observability.diagnostics.LoggingDiagnosticSink;
import com.iocextractor.observability.logging.LoggingPipelineObserver;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        return "jdk".equalsIgnoreCase(props.engine())
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
        Map<String, FeaturePredicate> registry = FeaturePredicates.defaults();
        List<MatchRule> rules = props.classify().rules().stream()
                .map(rule -> new MatchRule(
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
    public SourceReader sourceReader(IocProperties props) {
        return new TikaSourceReader(sourceCharset(props));
    }

    @Bean
    @Primary
    public LookupRepository lookupRepository(IocProperties props,
                                             ObjectProvider<JdbcLookupRepository> jdbcLookupRepository) {
        if (isDataframeJdbc(props)) {
            return jdbcLookupRepository.getObject();
        }
        Charset charset = csvCharset(props);
        Map<String, Path> artifactPaths = lookupArtifactPaths(props);
        if (artifactPaths.isEmpty() && !"daemon".equalsIgnoreCase(props.runtime().mode())) {
            return new CsvMaskLookupRepository(Path.of(props.lookup().path()), charset);
        }
        return new CsvArtifactLookupRepository(artifactPaths, charset);
    }

    @Bean
    @ConditionalOnDataframeStorage
    public JdbcLookupRepository jdbcLookupRepository(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation) {
        return new JdbcLookupRepository(dataframeStorageDataSource);
    }

    @Bean
    @Primary
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DiagnosticRenderer diagnosticRenderer() {
        return new TemplateDiagnosticRenderer();
    }

    @Bean
    public DiagnosticSink diagnosticSink(DiagnosticRenderer renderer) {
        return new LoggingDiagnosticSink(LoggerFactory.getLogger(LoggingDiagnosticSink.class), renderer);
    }

    @Bean
    public IocExtractionServiceFactory iocExtractionServiceFactory(SourceReader reader,
                                                                   Refanger refanger,
                                                                   IndicatorExtractor extractor,
                                                                   SourceAttributor attributor,
                                                                   LookupRepository lookup,
                                                                   DiagnosticSink diagnosticSink,
                                                                   IocProperties props) {
        return new IocExtractionServiceFactory(reader, refanger, extractor, attributor,
                lookup, props.lookup().deduplicate(), props.observability().mode(),
                new LoggingPipelineObserver(), diagnosticSink);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "oneshot", matchIfMissing = true)
    public ExtractIocsUseCase extractIocsUseCase(IocExtractionServiceFactory factory,
                                                 MatchPolicy matchPolicy,
                                                 IndicatorFeatureExtractor featureExtractor,
                                                 LookupRepository lookup,
                                                 ObjectProvider<JdbcCanonicalArtifactRepository>
                                                         jdbcCanonicalRepository,
                                                 ObjectProvider<CsvArtifactProjection> csvArtifactProjection,
                                                 IocProperties props) {
        List<IocSink> sinks = buildSinks(
                props,
                matchPolicy,
                featureExtractor,
                lookup,
                jdbcCanonicalRepository.getIfAvailable(),
                csvArtifactProjection.getIfAvailable());
        return factory.create(sinks);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public PartitionSinkFactory partitionSinkFactory(IocProperties props,
                                                     MatchPolicy matchPolicy,
                                                     IndicatorFeatureExtractor featureExtractor,
                                                     LookupRepository lookup) {
        return new PartitionedCsvSinkFactory(
                Path.of(props.ingestion().output().partitionsDir()),
                artifactDefinitions(props, matchPolicy, featureExtractor, lookup),
                writeFormat(props.sink().csv()),
                csvCharset(props));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "('${ioc.ingestion.ledger.type:file}' == 'jdbc' || '${ioc.aggregation.enabled:false}' == 'true')")
    public HikariDataSource serviceStorageDataSource(IocProperties props) {
        IocProperties.Storage.Service service = props.storage().service();
        if (!"jdbc".equalsIgnoreCase(service.type())) {
            throw new IocExtractorException("ioc.storage.service.type must be 'jdbc' for daemon service storage");
        }
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                "service",
                service.url(),
                service.sqlite().tuning(),
                service.pool().writeMax(),
                service.pool().readMax()));
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "('${ioc.ingestion.ledger.type:file}' == 'jdbc' || '${ioc.aggregation.enabled:false}' == 'true')")
    public SchemaMigrationResult serviceSchemaMigration(
                                                        @Qualifier("serviceStorageDataSource")
                                                        HikariDataSource serviceStorageDataSource,
                                                        DiagnosticSink diagnosticSink,
                                                        Clock clock) {
        return new SqliteUserVersionSchemaMigrator(
                serviceStorageDataSource,
                ServiceSchemaMigrations.sqlite(),
                diagnosticSink,
                new DiagnosticFactory(clock),
                "service").migrate();
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
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "('${ioc.ingestion.ledger.type:file}' == 'jdbc' || '${ioc.aggregation.enabled:false}' == 'true')")
    public JdbcStorageHealthProbe serviceStorageHealthProbe(@Qualifier("serviceStorageDataSource")
                                                            HikariDataSource serviceStorageDataSource,
                                                            @Qualifier("serviceSchemaMigration")
                                                            SchemaMigrationResult serviceSchemaMigration) {
        return new JdbcStorageHealthProbe(serviceStorageDataSource, "service");
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.aggregation.enabled:false}' == 'true'")
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
     * daemon consumes the inbox — an {@code ApplicationRunner} (run after lifecycle
     * start) would not. The returned summary bean is just the instantiation marker.
     */
    @Bean
    @ConditionalOnJdbcLedger
    public LegacyLedgerImporter.LegacyLedgerImportSummary legacyLedgerImport(LegacyLedgerImporter importer) {
        return importer.importAll();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnDataframeStorage
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
    @ConditionalOnDataframeStorage
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
    @ConditionalOnDataframeStorage
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
    @ConditionalOnDataframeStorage
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
    @ConditionalOnDataframeStorage
    public List<StoredArtifactIdentity> artifactIdentityValidation(
            ArtifactIdentityStore artifactIdentityStore,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            IocProperties props) {
        return artifactIdentityStore.ensureAll(artifactIdentityDefinitions(props));
    }

    @Bean
    @Primary
    @ConditionalOnDataframeStorage
    public JdbcCanonicalArtifactRepository jdbcCanonicalArtifactRepository(
            @Qualifier("dataframeStorageDataSource") HikariDataSource dataframeStorageDataSource,
            DataframeSchemaPlan dataframeSchemaReconciliation,
            ArtifactIdentityResolver artifactIdentityResolver,
            IocProperties props,
            Clock clock) {
        return new JdbcCanonicalArtifactRepository(
                dataframeStorageDataSource,
                dataframeSchemas(props),
                artifactIdentityResolver,
                clock);
    }

    @Bean
    @ConditionalOnDataframeStorage
    public CsvArtifactProjection csvArtifactProjection(JdbcCanonicalArtifactRepository jdbcCanonicalArtifactRepository,
                                                       IocProperties props) {
        return new CsvArtifactProjection(
                jdbcCanonicalArtifactRepository,
                artifactHeaders(props),
                canonicalArtifactPaths(props),
                writeFormat(props.sink().csv()),
                csvCharset(props));
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.aggregation.enabled:false}' == 'true'")
    public Integer aggregationRunRecovery(RunLedger runLedger, ArtifactProjection csvArtifactProjection) {
        return new AggregationRunRecoveryService(runLedger, csvArtifactProjection).recover();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public IngestionService ingestionService(IngestionLedger ledger,
                                             SourceLifecycle sourceLifecycle,
                                             PartitionSinkFactory partitionSinkFactory,
                                             IocExtractionServiceFactory extractionFactory,
                                             AggregationTrigger aggregationTrigger) {
        return new IngestionService(ledger, sourceLifecycle, partitionSinkFactory, extractionFactory,
                aggregationTrigger);
    }

    /**
     * Trigger the ingest use case calls after a partition is ready. With
     * {@code ioc.aggregation.trigger=on-partition|both} this is the scheduler
     * (event-driven kick); with {@code interval} it is a no-op (timer only).
     */
    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public AggregationTrigger aggregationTrigger(ObjectProvider<DaemonAggregationScheduler> schedulerProvider,
                                                 IocProperties props) {
        DaemonAggregationScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler != null && !"interval".equals(aggregationTriggerMode(props))) {
            return scheduler;
        }
        return AggregationTrigger.noop();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public CsvArtifactRepositories csvArtifactRepositories(IocProperties props,
                                                           MatchPolicy matchPolicy,
                                                           IndicatorFeatureExtractor featureExtractor,
                                                           LookupRepository lookup) {
        validateAggregationConfig(props);
        return new CsvArtifactRepositories(
                artifactDefinitions(props, matchPolicy, featureExtractor, lookup),
                canonicalArtifactPaths(props),
                readFormat(props.sink().csv()),
                writeFormat(props.sink().csv()),
                csvCharset(props));
    }

    @Bean
    public ArtifactIdentityResolver artifactIdentityResolver(IocProperties props) {
        return new CanonicalArtifactIdentityResolver(artifactIdentityDefinitions(props));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public StableIdIndex stableIdIndex(IocProperties props, Clock clock) {
        return new CsvStableIdIndex(Path.of(props.aggregation().idIndex().path()), clock, csvCharset(props));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public AggregatePartitionsUseCase aggregatePartitionsUseCase(IngestionLedger ledger,
                                                                 PartitionArtifactRepository partitionRepository,
                                                                 CanonicalArtifactRepository canonicalRepository,
                                                                 ArtifactIdentityResolver identityResolver,
                                                                 StableIdIndex stableIdIndex,
                                                                 ObjectProvider<ArtifactProjection> projection,
                                                                 ObjectProvider<RunLedger> runLedger,
                                                                 IocProperties props) {
        return new AggregationService(
                ledger,
                partitionRepository,
                canonicalRepository,
                identityResolver,
                stableIdIndex,
                new KeepFirstMergePolicy(),
                enabledArtifactNames(props),
                projection.getIfAvailable(NoopArtifactProjection::new),
                runLedger.getIfAvailable(NoopRunLedger::new));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public AggregationState aggregationState(Clock clock) {
        return new AggregationState(clock);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && '${ioc.aggregation.enabled}' == 'true'")
    public DaemonAggregationScheduler daemonAggregationScheduler(AggregatePartitionsUseCase useCase,
                                                                 AggregationState state,
                                                                 IocProperties props) {
        return new DaemonAggregationScheduler(useCase, state,
                props.aggregation().interval(), props.aggregation().initialDelay(),
                !"on-partition".equals(aggregationTriggerMode(props)));
    }

    /** Normalize + validate {@code ioc.aggregation.trigger} (default {@code both}). */
    private String aggregationTriggerMode(IocProperties props) {
        String value = props.aggregation().trigger();
        String mode = (value == null || value.isBlank()) ? "both" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("interval", "on-partition", "both").contains(mode)) {
            throw new IocExtractorException("Unknown ioc.aggregation.trigger: '" + value
                    + "' (use interval | on-partition | both)");
        }
        return mode;
    }

    @Bean
    @ConditionalOnFileLedger
    public IngestionLedgerHealthIndicator ingestionLedgerHealthIndicator(IocProperties props) {
        return new IngestionLedgerHealthIndicator(props);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "('${ioc.ingestion.ledger.type:file}' == 'jdbc' || '${ioc.aggregation.enabled:false}' == 'true')")
    public JdbcStorageHealthIndicator jdbcStorageHealthIndicator(JdbcStorageHealthProbe serviceStorageHealthProbe) {
        return new JdbcStorageHealthIndicator(serviceStorageHealthProbe);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public ArtifactStorageHealthIndicator artifactStorageHealthIndicator(IocProperties props) {
        return new ArtifactStorageHealthIndicator(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public AggregationHealthIndicator aggregationHealthIndicator(AggregationState state) {
        return new AggregationHealthIndicator(state);
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
                                                   IngestionLedger ledger,
                                                   IocProperties props,
                                                   Clock clock) {
        return new RetentionService(
                store,
                retentionTargets(props),
                clock,
                new AggregatedPartitionRetentionEligibility(ledger));
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
            RetentionAction action = "archive".equalsIgnoreCase(blankToEmpty(target.action()))
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

    // ---- artifact assembly -------------------------------------------------

    private List<IocSink> buildSinks(IocProperties props,
                                     MatchPolicy matchPolicy,
                                     IndicatorFeatureExtractor featureExtractor,
                                     LookupRepository lookup,
                                     JdbcCanonicalArtifactRepository jdbcCanonicalRepository,
                                     CsvArtifactProjection csvArtifactProjection) {
        CSVFormat writeFormat = writeFormat(props.sink().csv());
        Charset charset = csvCharset(props);
        return artifactDefinitions(props, matchPolicy, featureExtractor, lookup).stream()
                .map(artifact -> {
                    if (isDataframeJdbc(props)) {
                        if (jdbcCanonicalRepository == null || csvArtifactProjection == null) {
                            throw new IocExtractorException("Dataframe JDBC storage is enabled but repository "
                                    + "or CSV projection is not wired");
                        }
                        return new JdbcIocSink(
                                artifact.name(),
                                artifact.accepts(),
                                artifact.filter()::accepts,
                                artifact.mapper().header(),
                                artifact.mapper()::toRow,
                                new IdGenerator(artifact.idStrategy(), artifact.idStart())::next,
                                jdbcCanonicalRepository,
                                csvArtifactProjection::project);
                    }
                    return new CsvIocSink(
                            artifact.name(),
                            Path.of(findArtifactPath(props, artifact.name())),
                            artifact.accepts(),
                            artifact.filter(),
                            artifact.mapper(),
                            new IdGenerator(artifact.idStrategy(), artifact.idStart()),
                            writeFormat,
                            charset);
                })
                .map(IocSink.class::cast)
                .toList();
    }

    private List<CsvArtifactDefinition> artifactDefinitions(IocProperties props,
                                                            MatchPolicy matchPolicy,
                                                            IndicatorFeatureExtractor featureExtractor,
                                                            LookupRepository lookup) {
        Map<String, ValueProvider> providers = valueProviders(matchPolicy, featureExtractor);
        Map<String, Transform> transforms = transforms();
        Map<String, Predicate<Indicator>> filters = artifactFilters(featureExtractor);
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
                    startOf(artifact.name(), artifact.id(), lookup)));
        }
        return artifacts;
    }

    private Map<String, Path> lookupArtifactPaths(IocProperties props) {
        Map<String, Path> paths = new HashMap<>();
        if (props.lookup().artifacts() != null) {
            for (IocProperties.Lookup.Artifact artifact : props.lookup().artifacts()) {
                paths.put(artifact.name(), Path.of(artifact.path()));
            }
        }
        if (!paths.containsKey("masks") && props.lookup().path() != null && !props.lookup().path().isBlank()) {
            paths.put("masks", Path.of(props.lookup().path()));
        }
        return paths;
    }

    private String findArtifactPath(IocProperties props, String name) {
        return props.sink().artifacts().stream()
                .filter(artifact -> artifact.name().equals(name))
                .findFirst()
                .map(IocProperties.Sink.Artifact::path)
                .orElseThrow(() -> new IocExtractorException("Unknown artifact: " + name));
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
                        artifact.columns().stream().map(IocProperties.Sink.Artifact.Column::name).toList());
            }
        }
        return headers;
    }

    private List<String> enabledArtifactNames(IocProperties props) {
        return props.sink().artifacts().stream()
                .filter(IocProperties.Sink.Artifact::enabled)
                .map(IocProperties.Sink.Artifact::name)
                .toList();
    }

    private boolean isDataframeJdbc(IocProperties props) {
        return "jdbc".equalsIgnoreCase(props.storage().dataframe().type());
    }

    private void validateAggregationConfig(IocProperties props) {
        for (IocProperties.Aggregation.Artifact artifact : props.aggregation().artifacts()) {
            if (!"keep-first".equalsIgnoreCase(artifact.conflictPolicy())) {
                throw new IocExtractorException("Unsupported aggregation conflict policy: "
                        + artifact.conflictPolicy());
            }
        }
    }

    private Map<String, ValueProvider> valueProviders(MatchPolicy matchPolicy,
                                                      IndicatorFeatureExtractor featureExtractor) {
        Map<String, ValueProvider> providers = new HashMap<>();
        providers.put("id", new IdValueProvider());
        providers.put("value", new IndicatorValueProvider());
        providers.put("source.label", new SourceLabelValueProvider());
        providers.put("match.url", new MatchUrlValueProvider(matchPolicy));
        providers.put("match.host", new MatchHostValueProvider(matchPolicy));
        providers.put("address.url", new AddressUrlValueProvider(featureExtractor));
        providers.put("address.ip", new AddressIpValueProvider(featureExtractor));
        return providers;
    }

    private Map<String, Predicate<Indicator>> artifactFilters(IndicatorFeatureExtractor featureExtractor) {
        Map<String, Predicate<Indicator>> filters = new HashMap<>();
        FeaturePredicates.defaults().forEach((key, predicate) ->
                filters.put(key, indicator -> predicate.test(featureExtractor.extract(indicator))));
        filters.put("is-bare-ip", indicator ->
                NetworkAddressClassifier.isBareIp(indicator, featureExtractor.extract(indicator)));
        return filters;
    }

    private ArtifactFilter artifactFilter(IocProperties.Sink.Artifact artifact,
                                          Map<String, Predicate<Indicator>> filters) {
        return new ArtifactFilter(
                resolveArtifactPredicates(artifact.include(), filters),
                resolveArtifactPredicates(artifact.exclude(), filters));
    }

    private List<Predicate<Indicator>> resolveArtifactPredicates(List<String> keys,
                                                                 Map<String, Predicate<Indicator>> filters) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Predicate<Indicator>> predicates = new ArrayList<>();
        for (String key : keys) {
            Predicate<Indicator> predicate = filters.get(key);
            if (predicate == null) {
                throw new IocExtractorException("Unknown artifact filter predicate: " + key);
            }
            predicates.add(predicate);
        }
        return predicates;
    }

    private Map<String, Transform> transforms() {
        Map<String, Transform> transforms = new HashMap<>();
        transforms.put("lower", new LowercaseTransform());
        transforms.put("lower-host", new LowerHostTransform());
        transforms.put("upper", new UppercaseTransform());
        transforms.put("strip-prefix", new StripPrefixTransform());
        return transforms;
    }

    private List<ColumnSpec> columnSpecs(IocProperties.Sink.Artifact artifact) {
        return artifact.columns().stream()
                .map(column -> new ColumnSpec(column.name(), column.from(),
                        column.value(), column.whenType(), column.transform()))
                .toList();
    }

    private List<DataframeArtifactSchema> dataframeSchemas(IocProperties props) {
        return props.sink().artifacts().stream()
                .filter(IocProperties.Sink.Artifact::enabled)
                .map(artifact -> new DataframeArtifactSchema(
                        artifact.name(),
                        artifact.columns().stream()
                                .map(column -> new DataframeColumn(column.name(), column.type()))
                                .toList()))
                .toList();
    }

    private List<ArtifactIdentityDefinition> artifactIdentityDefinitions(IocProperties props) {
        return props.aggregation().artifacts().stream()
                .map(artifact -> new ArtifactIdentityDefinition(
                        artifact.name(),
                        artifact.keyColumns(),
                        "first-non-empty".equalsIgnoreCase(blankToEmpty(artifact.keyMode())),
                        artifact.epoch() == null ? 1 : artifact.epoch()))
                .toList();
    }

    private IdGenerator.Strategy strategyOf(IocProperties.Sink.Artifact.Id id) {
        return id != null && "descending".equalsIgnoreCase(id.strategy())
                ? IdGenerator.Strategy.DESCENDING
                : IdGenerator.Strategy.ASCENDING;
    }

    /**
     * Resolve the starting id. {@code auto} continues the ascending sequence from
     * the named artifact's current max id (+1); a numeric value is used verbatim.
     */
    private long startOf(String artifactName, IocProperties.Sink.Artifact.Id id, LookupRepository lookup) {
        if (id == null || id.start() == null) {
            return lookup.maxId(artifactName) + 1;
        }
        String start = id.start().trim();
        if (start.equalsIgnoreCase("auto")) {
            return lookup.maxId(artifactName) + 1;
        }
        try {
            return Long.parseLong(start);
        } catch (NumberFormatException ignored) {
            return lookup.maxId(artifactName) + 1;
        }
    }

    /** A blank/absent mask code means "no match" -> rendered as the CSV null literal. */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Output charset for all CSV artifacts (writers) and for reading existing
     * artifacts in lookup/aggregation, so read and write always agree. Blank or
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

    private CSVFormat readFormat(IocProperties.Sink.Csv csv) {
        return CSVFormat.Builder.create()
                .setDelimiter(csv.delimiter().charAt(0))
                .setQuote(csv.quote().charAt(0))
                .setNullString(csv.nullLiteral())
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
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
