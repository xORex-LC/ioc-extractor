package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.MaskMatch;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Row mapper for the network reputation-mask artifact ({@code repListMasks}).
 * Columns: id;mask;url_match;host_match;score;time_last_seen;time_first_seen;
 *          threat_type;source;description
 */
public final class NetworkMaskRowMapper implements RowMapper {

    private static final List<String> HEADER = List.of(
            "id", "mask", "url_match", "host_match", "score",
            "time_last_seen", "time_first_seen", "threat_type", "source", "description");

    private final MatchPolicy matchPolicy;
    private final boolean lowerCase;

    public NetworkMaskRowMapper(MatchPolicy matchPolicy, boolean lowerCase) {
        this.matchPolicy = matchPolicy;
        this.lowerCase = lowerCase;
    }

    @Override
    public List<String> header() {
        return HEADER;
    }

    @Override
    public List<String> toRow(long id, Indicator indicator) {
        String mask = lowerCase ? indicator.value().toLowerCase(Locale.ROOT) : indicator.value();
        MaskMatch match = matchPolicy.classify(indicator);
        // Arrays.asList tolerates nulls -> rendered as the CSV null literal.
        return Arrays.asList(
                Long.toString(id),
                mask,
                match.urlMatch(),
                match.hostMatch(),
                null,                       // score
                null,                       // time_last_seen
                null,                       // time_first_seen
                null,                       // threat_type
                indicator.source().label(),
                null);                      // description
    }
}
