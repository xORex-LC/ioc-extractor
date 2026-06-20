package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.refang.Refanger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application core: the ETL pipeline expressed against ports only.
 *
 * <pre>
 *   read → refang → extract → attribute → deduplicate → sink(s)
 * </pre>
 *
 * Framework-free by design; wired in the composition root (bootstrap).
 */
public final class IocExtractionService implements ExtractIocsUseCase {

    private static final Logger log = LoggerFactory.getLogger(IocExtractionService.class);

    private final SourceReader reader;
    private final Refanger refanger;
    private final IndicatorExtractor extractor;
    private final SourceAttributor attributor;
    private final LookupRepository lookup;
    private final List<IocSink> sinks;
    private final boolean deduplicate;

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                LookupRepository lookup,
                                List<IocSink> sinks,
                                boolean deduplicate) {
        this.reader = reader;
        this.refanger = refanger;
        this.extractor = extractor;
        this.attributor = attributor;
        this.lookup = lookup;
        this.sinks = List.copyOf(sinks);
        this.deduplicate = deduplicate;
    }

    @Override
    public ExtractionResult extract(ExtractionCommand command) {
        log.info("Extracting IOCs from {}", command.source());

        String rawText = reader.readText(command.source());
        String refanged = refanger.refang(rawText);

        List<RawIndicator> raw = extractor.extract(refanged);
        List<Indicator> attributed = attributor.attribute(refanged, raw);
        List<Indicator> retained = deduplicate ? deduplicate(attributed) : attributed;

        log.info("Extracted {} indicators, {} retained after de-dup", attributed.size(), retained.size());

        Map<String, Integer> written = new LinkedHashMap<>();
        if (!command.dryRun()) {
            for (IocSink sink : sinks) {
                int count = sink.write(retained);
                written.put(sink.name(), count);
                log.info("Artifact '{}' <- {} rows", sink.name(), count);
            }
        } else {
            log.info("Dry-run: no artifacts written");
        }

        return new ExtractionResult(attributed.size(), retained.size(), written);
    }

    /** Drop within-batch duplicates and indicators already present in storage. */
    private List<Indicator> deduplicate(List<Indicator> indicators) {
        Set<String> seen = new HashSet<>();
        List<Indicator> out = new ArrayList<>(indicators.size());
        for (Indicator indicator : indicators) {
            if (!seen.add(indicator.dedupKey())) {
                continue;
            }
            if (lookup.contains(indicator)) {
                continue;
            }
            out.add(indicator);
        }
        return out;
    }
}
