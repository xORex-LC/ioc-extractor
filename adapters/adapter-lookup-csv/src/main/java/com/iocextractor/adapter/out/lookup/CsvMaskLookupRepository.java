package com.iocextractor.adapter.out.lookup;

import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@link LookupRepository} backed by the existing mask CSV artifact (the current
 * "storage"). Loads the {@code mask} column once for O(1) existence checks and
 * the highest {@code id} so a sink can continue the ascending id sequence.
 * A missing file is treated as empty storage (no cross-run dedup yet).
 *
 * <p>NOTE: mask-schema specific by design; a hash-aware lookup would be a
 * separate adapter behind the same port.
 */
public final class CsvMaskLookupRepository implements LookupRepository {

    private static final Logger log = LoggerFactory.getLogger(CsvMaskLookupRepository.class);

    private final Set<String> masks = new HashSet<>();
    private final Charset charset;
    private long maxId;

    public CsvMaskLookupRepository(Path path) {
        this(path, StandardCharsets.UTF_8);
    }

    public CsvMaskLookupRepository(Path path, Charset charset) {
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
        load(path);
    }

    @Override
    public boolean contains(Indicator indicator) {
        return masks.contains(indicator.value().toLowerCase(Locale.ROOT));
    }

    @Override
    public long maxId() {
        return maxId;
    }

    private void load(Path path) {
        if (path == null || !Files.exists(path)) {
            LogEvents.info(log)
                    .action(EventAction.LOOKUP_LOAD)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, 0)
                    .message("lookup artifact not found; starting with empty storage")
                    .log();
            return;
        }
        CSVFormat format = CSVFormat.Builder.create()
                .setDelimiter(';')
                .setQuote('"')
                .setNullString("NULL")
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        try (Reader reader = Files.newBufferedReader(path, charset);
             CSVParser parser = CSVParser.parse(reader, format)) {
            for (CSVRecord record : parser) {
                String mask = record.isMapped("mask") ? record.get("mask") : null;
                if (mask != null) {
                    masks.add(mask.toLowerCase(Locale.ROOT));
                }
                maxId = Math.max(maxId, parseId(record));
            }
        } catch (IOException e) {
            LogEvents.error(log)
                    .action(EventAction.LOOKUP_LOAD)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.FILE_PATH, path)
                    .message("lookup load failed")
                    .log(e);
            throw new IocExtractorException("Failed to load lookup artifact: " + path, e);
        }
        LogEvents.info(log)
                .action(EventAction.LOOKUP_LOAD)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.FILE_PATH, path)
                .field(LogField.IOC_ROWS, masks.size())
                .message("lookup loaded")
                .log();
    }

    private long parseId(CSVRecord record) {
        if (!record.isMapped("id")) {
            return 0L;
        }
        try {
            return Long.parseLong(record.get("id").trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0L;
        }
    }
}
