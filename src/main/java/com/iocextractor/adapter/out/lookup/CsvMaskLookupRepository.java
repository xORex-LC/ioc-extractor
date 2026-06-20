package com.iocextractor.adapter.out.lookup;

import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@link LookupRepository} backed by the existing mask CSV artifact (the current
 * "storage"). Loads the {@code mask} column once for O(1) existence checks.
 * A missing file is treated as empty storage (no cross-run dedup yet).
 *
 * <p>NOTE: mask-schema specific by design; a hash-aware lookup would be a
 * separate adapter behind the same port.
 */
public final class CsvMaskLookupRepository implements LookupRepository {

    private static final Logger log = LoggerFactory.getLogger(CsvMaskLookupRepository.class);

    private final Set<String> masks;

    public CsvMaskLookupRepository(Path path) {
        this.masks = load(path);
    }

    @Override
    public boolean contains(Indicator indicator) {
        return masks.contains(indicator.value().toLowerCase(Locale.ROOT));
    }

    private Set<String> load(Path path) {
        Set<String> result = new HashSet<>();
        if (path == null || !Files.exists(path)) {
            log.info("Lookup artifact {} not found; starting with empty storage", path);
            return result;
        }
        CSVFormat format = CSVFormat.Builder.create()
                .setDelimiter(';')
                .setQuote('"')
                .setNullString("NULL")
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            for (CSVRecord record : parser) {
                String mask = record.isMapped("mask") ? record.get("mask") : null;
                if (mask != null) {
                    result.add(mask.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to load lookup artifact: " + path, e);
        }
        log.info("Loaded {} existing masks for de-duplication", result.size());
        return result;
    }
}
