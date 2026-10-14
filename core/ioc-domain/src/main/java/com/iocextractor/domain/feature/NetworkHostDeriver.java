package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.IndicatorType;
import java.util.Objects;

/** Derives one host indicator while retaining the original source attribution. */
public final class NetworkHostDeriver {
    private final NetworkAddressParser parser;

    public NetworkHostDeriver(NetworkAddressParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** Returns either one derived indicator or an expected input-dependent reason. */
    public Result derive(Indicator original) {
        Objects.requireNonNull(original, "original");
        if (original.type().category() != IndicatorCategory.NETWORK) {
            return new Result(null, NetworkAddressParser.FailureReason.UNSUPPORTED_ADDRESS_FORM);
        }
        NetworkAddressParser.Result parsed = parser.parse(original.value());
        if (!parsed.isAvailable()) {
            return new Result(null, parsed.failure());
        }
        IndicatorType type = parsed.address().isIpv4() ? IndicatorType.IPV4 : IndicatorType.DOMAIN;
        return new Result(new Indicator(parsed.address().host(), type, original.source()), null);
    }

    public record Result(Indicator indicator, NetworkAddressParser.FailureReason failure) {
        public Result {
            if ((indicator == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one host derivation outcome is required");
            }
        }

        public boolean isAvailable() {
            return indicator != null;
        }
    }
}
