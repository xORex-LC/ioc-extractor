package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.lookup.CsvMaskLookupRepository;
import com.iocextractor.adapter.out.regex.JdkRegexPatternEngine;
import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.adapter.out.sink.csv.CsvIocSink;
import com.iocextractor.adapter.out.sink.csv.FileHashRowMapper;
import com.iocextractor.adapter.out.sink.csv.IdGenerator;
import com.iocextractor.adapter.out.sink.csv.NetworkMaskRowMapper;
import com.iocextractor.adapter.out.sink.csv.RowMapper;
import com.iocextractor.adapter.out.source.TikaSourceReader;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.service.IocExtractionService;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.attribute.MarkerSourceAttributor;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.classify.DefaultMatchPolicy;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RegexIndicatorExtractor;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangRule;
import com.iocextractor.domain.refang.ReplacementRefanger;
import com.iocextractor.domain.refang.Refanger;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
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
    public MatchPolicy matchPolicy(IocProperties props) {
        IocProperties.Classify.Codes bare = props.classify().bareHost();
        IocProperties.Classify.Codes full = props.classify().fullUrl();
        return new DefaultMatchPolicy(
                new MaskMatch(blankToNull(bare.urlMatch()), blankToNull(bare.hostMatch())),
                new MaskMatch(blankToNull(full.urlMatch()), blankToNull(full.hostMatch())));
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
    public ExtractIocsUseCase extractIocsUseCase(SourceReader reader,
                                                 Refanger refanger,
                                                 IndicatorExtractor extractor,
                                                 SourceAttributor attributor,
                                                 LookupRepository lookup,
                                                 MatchPolicy matchPolicy,
                                                 IocProperties props) {
        List<IocSink> sinks = buildSinks(props, matchPolicy);
        return new IocExtractionService(reader, refanger, extractor, attributor,
                lookup, sinks, props.lookup().deduplicate());
    }

    // ---- artifact assembly -------------------------------------------------

    private List<IocSink> buildSinks(IocProperties props, MatchPolicy matchPolicy) {
        CSVFormat writeFormat = writeFormat(props.sink().csv());
        List<IocSink> sinks = new ArrayList<>();
        for (IocProperties.Sink.Artifact artifact : props.sink().artifacts()) {
            if (!artifact.enabled()) {
                continue;
            }
            RowMapper mapper = mapperFor(artifact, matchPolicy);
            IdGenerator ids = new IdGenerator(strategyOf(artifact.id()), startOf(artifact.id()));
            sinks.add(new CsvIocSink(
                    artifact.name(),
                    Path.of(artifact.path()),
                    EnumSet.copyOf(artifact.accepts()),
                    mapper,
                    ids,
                    writeFormat));
        }
        return sinks;
    }

    private RowMapper mapperFor(IocProperties.Sink.Artifact artifact, MatchPolicy matchPolicy) {
        boolean lower = "lower".equalsIgnoreCase(artifact.valueCase());
        boolean upper = "upper".equalsIgnoreCase(artifact.valueCase());
        return switch (artifact.mapper()) {
            case "network-mask" -> new NetworkMaskRowMapper(matchPolicy, lower);
            case "file-hash" -> new FileHashRowMapper(upper, artifact.sourceStripPrefix());
            default -> throw new IocExtractorException("Unknown row mapper: " + artifact.mapper());
        };
    }

    private IdGenerator.Strategy strategyOf(IocProperties.Sink.Artifact.Id id) {
        return id != null && "descending".equalsIgnoreCase(id.strategy())
                ? IdGenerator.Strategy.DESCENDING
                : IdGenerator.Strategy.ASCENDING;
    }

    /** TODO: 'auto' should continue from the lookup's current max id. */
    private long startOf(IocProperties.Sink.Artifact.Id id) {
        if (id == null || id.start() == null) {
            return 1L;
        }
        try {
            return Long.parseLong(id.start().trim());
        } catch (NumberFormatException ignored) {
            return 1L;
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
