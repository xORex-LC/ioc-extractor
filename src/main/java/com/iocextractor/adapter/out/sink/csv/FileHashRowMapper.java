package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Row mapper for the file-hash artifact ({@code hashes}).
 * Columns: id;hash_md5;hash_sha256;hash_sha1;score;time_last_seen;
 *          time_first_seen;threat_type;source;description
 *
 * <p>Hashes are stored upper-case in the reference, and this artifact's
 * {@code source} drops the leading "Письмо " prefix that masks keep.
 */
public final class FileHashRowMapper implements RowMapper {

    private static final List<String> HEADER = List.of(
            "id", "hash_md5", "hash_sha256", "hash_sha1", "score",
            "time_last_seen", "time_first_seen", "threat_type", "source", "description");

    private final boolean upperCase;
    private final String sourceStripPrefix;

    public FileHashRowMapper(boolean upperCase, String sourceStripPrefix) {
        this.upperCase = upperCase;
        this.sourceStripPrefix = sourceStripPrefix;
    }

    @Override
    public List<String> header() {
        return HEADER;
    }

    @Override
    public List<String> toRow(long id, Indicator indicator) {
        String value = upperCase
                ? indicator.value().toUpperCase(Locale.ROOT)
                : indicator.value();
        IndicatorType type = indicator.type();
        return Arrays.asList(
                Long.toString(id),
                type == IndicatorType.MD5 ? value : null,
                type == IndicatorType.SHA256 ? value : null,
                type == IndicatorType.SHA1 ? value : null,
                null,                       // score
                null,                       // time_last_seen
                null,                       // time_first_seen
                null,                       // threat_type
                strip(indicator.source().label()),
                null);                      // description
    }

    private String strip(String label) {
        if (sourceStripPrefix != null && label != null && label.startsWith(sourceStripPrefix)) {
            return label.substring(sourceStripPrefix.length());
        }
        return label;
    }
}
