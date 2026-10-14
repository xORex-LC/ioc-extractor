package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.IndicatorType;
import java.util.Objects;

/** Removes only a supported scheme, preserving the address suffix and source. */
public final class NetworkSchemeDeriver {
    private static final String SCHEME_SEPARATOR = "://";
    private final NetworkAddressParser parser;

    public NetworkSchemeDeriver(NetworkAddressParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** Validates the entire address before removing its optional HTTP(S) scheme. */
    public Result derive(Indicator original) {
        Objects.requireNonNull(original, "original");
        if (original.type().category() != IndicatorCategory.NETWORK) {
            return new Result(null, NetworkAddressParser.FailureReason.UNSUPPORTED_ADDRESS_FORM);
        }
        var parsed = parser.parse(original.value());
        if (!parsed.isAvailable()) {
            return new Result(null, parsed.failure());
        }
        int separator = original.value().indexOf(SCHEME_SEPARATOR);
        String value = separator < 0 ? original.value()
                : original.value().substring(separator + SCHEME_SEPARATOR.length());
        var address = parsed.address();
        boolean detailed = address.hasPort() || address.hasPath() || address.hasQuery() || address.hasFragment();
        IndicatorType type = detailed ? IndicatorType.URL
                : address.isIpv4() ? IndicatorType.IPV4 : IndicatorType.DOMAIN;
        return new Result(new Indicator(value, type, original.source()), null);
    }

    /** Exactly one derived value or expected validation failure. */
    public record Result(Indicator indicator, NetworkAddressParser.FailureReason failure) {
        public Result {
            if ((indicator == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one scheme derivation outcome is required");
            }
        }

        public boolean isAvailable() {
            return indicator != null;
        }
    }
}
