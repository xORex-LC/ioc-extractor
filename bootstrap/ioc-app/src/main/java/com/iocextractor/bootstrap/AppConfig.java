package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.lookup.CsvMaskLookupRepository;
import com.iocextractor.adapter.out.regex.JdkRegexPatternEngine;
import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.adapter.out.sink.csv.ColumnSpec;
import com.iocextractor.adapter.out.sink.csv.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.CsvIocSink;
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
import com.iocextractor.application.ingest.IngestionService;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.PartitionSinkFactory;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.common.IocExtractorException;
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
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RegexIndicatorExtractor;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangRule;
import com.iocextractor.domain.refang.ReplacementRefanger;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.observability.diagnostics.LoggingDiagnosticSink;
import com.iocextractor.observability.logging.LoggingPipelineObserver;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public SourceReader sourceReader() {
        return new TikaSourceReader();
    }

    @Bean
    public LookupRepository lookupRepository(IocProperties props) {
        return new CsvMaskLookupRepository(Path.of(props.lookup().path()));
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
    public ExtractIocsUseCase extractIocsUseCase(IocExtractionServiceFactory factory,
                                                 MatchPolicy matchPolicy,
                                                 LookupRepository lookup,
                                                 IocProperties props) {
        List<IocSink> sinks = buildSinks(props, matchPolicy, lookup.maxId());
        return factory.create(sinks);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public PartitionSinkFactory partitionSinkFactory(IocProperties props,
                                                     MatchPolicy matchPolicy,
                                                     LookupRepository lookup) {
        return new PartitionedCsvSinkFactory(
                Path.of(props.ingestion().output().partitionsDir()),
                artifactDefinitions(props, matchPolicy, lookup.maxId()),
                writeFormat(props.sink().csv()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    public IngestionService ingestionService(IngestionLedger ledger,
                                             SourceLifecycle sourceLifecycle,
                                             PartitionSinkFactory partitionSinkFactory,
                                             IocExtractionServiceFactory extractionFactory) {
        return new IngestionService(ledger, sourceLifecycle, partitionSinkFactory, extractionFactory);
    }

    // ---- artifact assembly -------------------------------------------------

    private List<IocSink> buildSinks(IocProperties props, MatchPolicy matchPolicy, long autoBase) {
        CSVFormat writeFormat = writeFormat(props.sink().csv());
        return artifactDefinitions(props, matchPolicy, autoBase).stream()
                .map(artifact -> new CsvIocSink(
                        artifact.name(),
                        Path.of(findArtifactPath(props, artifact.name())),
                        artifact.accepts(),
                        artifact.mapper(),
                        new IdGenerator(artifact.idStrategy(), artifact.idStart()),
                        writeFormat))
                .map(IocSink.class::cast)
                .toList();
    }

    private List<CsvArtifactDefinition> artifactDefinitions(IocProperties props, MatchPolicy matchPolicy, long autoBase) {
        Map<String, ValueProvider> providers = valueProviders(matchPolicy);
        Map<String, Transform> transforms = transforms();
        List<CsvArtifactDefinition> artifacts = new ArrayList<>();
        for (IocProperties.Sink.Artifact artifact : props.sink().artifacts()) {
            if (!artifact.enabled()) {
                continue;
            }
            RowMapper mapper = new ConfigurableRowMapper(columnSpecs(artifact), providers, transforms);
            artifacts.add(new CsvArtifactDefinition(
                    artifact.name(),
                    EnumSet.copyOf(artifact.accepts()),
                    mapper,
                    strategyOf(artifact.id()),
                    startOf(artifact.id(), autoBase)));
        }
        return artifacts;
    }

    private String findArtifactPath(IocProperties props, String name) {
        return props.sink().artifacts().stream()
                .filter(artifact -> artifact.name().equals(name))
                .findFirst()
                .map(IocProperties.Sink.Artifact::path)
                .orElseThrow(() -> new IocExtractorException("Unknown artifact: " + name));
    }

    private Map<String, ValueProvider> valueProviders(MatchPolicy matchPolicy) {
        Map<String, ValueProvider> providers = new HashMap<>();
        providers.put("id", new IdValueProvider());
        providers.put("value", new IndicatorValueProvider());
        providers.put("source.label", new SourceLabelValueProvider());
        providers.put("match.url", new MatchUrlValueProvider(matchPolicy));
        providers.put("match.host", new MatchHostValueProvider(matchPolicy));
        return providers;
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

    private IdGenerator.Strategy strategyOf(IocProperties.Sink.Artifact.Id id) {
        return id != null && "descending".equalsIgnoreCase(id.strategy())
                ? IdGenerator.Strategy.DESCENDING
                : IdGenerator.Strategy.ASCENDING;
    }

    /**
     * Resolve the starting id. {@code auto} continues the ascending sequence from
     * the lookup's current max id (+1); a numeric value is used verbatim.
     */
    private long startOf(IocProperties.Sink.Artifact.Id id, long autoBase) {
        if (id == null || id.start() == null) {
            return autoBase + 1;
        }
        String start = id.start().trim();
        if (start.equalsIgnoreCase("auto")) {
            return autoBase + 1;
        }
        try {
            return Long.parseLong(start);
        } catch (NumberFormatException ignored) {
            return autoBase + 1;
        }
    }

    /** A blank/absent mask code means "no match" -> rendered as the CSV null literal. */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
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
