package com.iocextractor.application.dataframeimport.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * Stable external token owned by a framework-free dataframe-import policy.
 */
public interface ImportPolicyToken {

    /**
     * Returns the canonical kebab-case configuration and fingerprint token.
     *
     * @return stable token
     */
    String token();

    /**
     * Parses a policy token without depending on Spring conversion APIs.
     *
     * @param type enum type implementing this contract
     * @param value external token
     * @param label value label used in failure messages
     * @param <E> policy enum type
     * @return matching enum value
     * @throws IllegalArgumentException when the token is blank or unknown
     */
    static <E extends Enum<E> & ImportPolicyToken> E parse(Class<E> type, String value, String label) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (E candidate : type.getEnumConstants()) {
                if (candidate.token().equals(normalized)) {
                    return candidate;
                }
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants())
                .map(ImportPolicyToken::token)
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
        throw new IllegalArgumentException("Unsupported %s '%s'; expected one of: %s"
                .formatted(label, value, allowed));
    }
}
