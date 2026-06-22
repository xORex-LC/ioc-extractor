package com.iocextractor.adapter.out.lookup;

import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.IndicatorType;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Artifact-aware CSV lookup. Network indicators are checked against the masks
 * artifact, bare IP indicators against the IP artifact, while file indicators
 * are checked against hash columns.
 */
public final class CsvArtifactLookupRepository implements LookupRepository {

    private static final Logger log = LoggerFactory.getLogger(CsvArtifactLookupRepository.class);

    private final Set<String> masks = new HashSet<>();
    private final Set<String> ips = new HashSet<>();
    private final Set<String> md5 = new HashSet<>();
    private final Set<String> sha1 = new HashSet<>();
    private final Set<String> sha256 = new HashSet<>();
    private final Map<String, Long> maxIds = new HashMap<>();
    private final Charset charset;
    private long maxId;

    public CsvArtifactLookupRepository(Map<String, Path> artifactPaths) {
        this(artifactPaths, StandardCharsets.UTF_8);
    }

    public CsvArtifactLookupRepository(Map<String, Path> artifactPaths, Charset charset) {
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
        loadMasks(artifactPaths.get("masks"));
        loadIps(artifactPaths.get("ip_list"));
        loadHashes(artifactPaths.get("hashes"));
    }

    @Override
    public boolean contains(Indicator indicator) {
        if (indicator.type().category() == IndicatorCategory.NETWORK) {
            if (indicator.type() == IndicatorType.IPV4 && isBareIp(indicator.value())) {
                return ips.contains(indicator.value().toLowerCase(Locale.ROOT));
            }
            return masks.contains(indicator.value().toLowerCase(Locale.ROOT));
        }
        return switch (indicator.type()) {
            case MD5 -> md5.contains(indicator.value().toUpperCase(Locale.ROOT));
            case SHA1 -> sha1.contains(indicator.value().toUpperCase(Locale.ROOT));
            case SHA256 -> sha256.contains(indicator.value().toUpperCase(Locale.ROOT));
            default -> false;
        };
    }

    @Override
    public long maxId() {
        return maxId;
    }

    @Override
    public long maxId(String artifactName) {
        if (artifactName == null || artifactName.isBlank()) {
            return maxId();
        }
        return maxIds.getOrDefault(artifactName, maxId());
    }

    private void loadMasks(Path path) {
        long artifactMaxId = 0L;
        for (CSVRecord record : read(path)) {
            addIfPresent(record, "mask", masks, true);
            artifactMaxId = Math.max(artifactMaxId, parseId(record));
        }
        recordMaxId("masks", artifactMaxId);
        logLoaded(path, masks.size());
    }

    private void loadIps(Path path) {
        long artifactMaxId = 0L;
        for (CSVRecord record : read(path)) {
            addIfPresent(record, "ip", ips, true);
            artifactMaxId = Math.max(artifactMaxId, parseId(record));
        }
        recordMaxId("ip_list", artifactMaxId);
        logLoaded(path, ips.size());
    }

    private void loadHashes(Path path) {
        int before = md5.size() + sha1.size() + sha256.size();
        long artifactMaxId = 0L;
        for (CSVRecord record : read(path)) {
            addIfPresent(record, "hash_md5", md5, false);
            addIfPresent(record, "hash_sha1", sha1, false);
            addIfPresent(record, "hash_sha256", sha256, false);
            artifactMaxId = Math.max(artifactMaxId, parseId(record));
        }
        recordMaxId("hashes", artifactMaxId);
        logLoaded(path, md5.size() + sha1.size() + sha256.size() - before);
    }

    private Iterable<CSVRecord> read(Path path) {
        if (path == null || !Files.exists(path)) {
            LogEvents.info(log)
                    .action(EventAction.LOOKUP_LOAD)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, 0)
                    .message("lookup artifact not found; treating as empty")
                    .log();
            return Set.of();
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
            return parser.getRecords();
        } catch (IOException e) {
            LogEvents.error(log)
                    .action(EventAction.LOOKUP_LOAD)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.FILE_PATH, path)
                    .message("lookup load failed")
                    .log(e);
            throw new IocExtractorException("Failed to load lookup artifact: " + path, e);
        }
    }

    private void addIfPresent(CSVRecord record, String column, Set<String> values, boolean lower) {
        if (!record.isMapped(column)) {
            return;
        }
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            return;
        }
        values.add(lower ? value.toLowerCase(Locale.ROOT) : value.toUpperCase(Locale.ROOT));
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

    private void recordMaxId(String artifactName, long artifactMaxId) {
        maxIds.put(artifactName, artifactMaxId);
        maxId = Math.max(maxId, artifactMaxId);
    }

    private boolean isBareIp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.indexOf(':') < 0
                && value.indexOf('/') < 0
                && value.indexOf('?') < 0;
    }

    private void logLoaded(Path path, int rows) {
        LogEvents.info(log)
                .action(EventAction.LOOKUP_LOAD)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.FILE_PATH, path)
                .field(LogField.IOC_ROWS, rows)
                .message("lookup artifact loaded")
                .log();
    }
}
