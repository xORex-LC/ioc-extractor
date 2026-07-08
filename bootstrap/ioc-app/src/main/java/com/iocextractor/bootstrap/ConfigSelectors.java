package com.iocextractor.bootstrap;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Shared parser for early configuration reads that happen before properties bind. */
final class ConfigSelectors {

    private ConfigSelectors() {
    }

    static <E extends Enum<E> & ConfigSelector> E parse(Class<E> type, String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(type, value, label);
        }
        String candidate = normalize(value);
        for (E constant : type.getEnumConstants()) {
            if (candidate.equals(normalize(constant.token())) || candidate.equals(normalize(constant.name()))) {
                return constant;
            }
        }
        throw invalid(type, value, label);
    }

    static <E extends Enum<E> & ConfigSelector> String supportedValues(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(ConfigSelector::token)
                .collect(Collectors.joining(", "));
    }

    private static <E extends Enum<E> & ConfigSelector> IllegalArgumentException invalid(
            Class<E> type,
            String value,
            String label) {
        return new IllegalArgumentException("Invalid " + label + " value '" + value
                + "'; supported values: " + supportedValues(type));
    }

    private static String normalize(String value) {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
    }
}
