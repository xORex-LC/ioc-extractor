package com.iocextractor.processing.parse;

import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.IndicatorCategory;
import java.util.Objects;

/** Checks that one configured extractor match consumes an entire structured cell. */
public final class ExactIndicatorParser {
    private final IndicatorExtractor extractor;
    private final NetworkAddressParser addressParser;

    public ExactIndicatorParser(IndicatorExtractor extractor, NetworkAddressParser addressParser) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.addressParser = Objects.requireNonNull(addressParser, "addressParser");
    }

    /** Returns one complete IOC match or the input-dependent failure reason. */
    public Result parse(String cell) {
        if (cell == null || cell.isBlank()) {
            return new Result(null, FailureReason.NO_INDICATOR);
        }
        String value = cell.strip();
        var extracted = extractor.extract(value).indicators();
        if (extracted.size() != 1 || extracted.getFirst().position() != 0
                || extracted.getFirst().value().length() != value.length()) {
            return new Result(null, FailureReason.PARTIAL_OR_MULTIPLE_MATCH);
        }
        RawIndicator raw = extracted.getFirst();
        if (raw.type().category() == IndicatorCategory.NETWORK
                && !addressParser.parse(raw.value()).isAvailable()) {
            return new Result(null, FailureReason.INVALID_NETWORK_ADDRESS);
        }
        return new Result(raw, null);
    }

    public enum FailureReason { NO_INDICATOR, PARTIAL_OR_MULTIPLE_MATCH, INVALID_NETWORK_ADDRESS }

    public record Result(RawIndicator indicator, FailureReason failure) {
        public Result {
            if ((indicator == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one cell outcome is required");
            }
        }

        public boolean isAvailable() {
            return indicator != null;
        }
    }
}
