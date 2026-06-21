package com.iocextractor.domain.feature;

/**
 * Default {@link IndicatorNormalizer}: trims surrounding whitespace and the
 * punctuation that commonly bleeds in from document text ({@code ; , " '}).
 * Conservative — does not touch interior characters or a trailing dot.
 */
public final class DefaultIndicatorNormalizer implements IndicatorNormalizer {

    private static final String TRIM = ";,\"' \t\r\n";

    @Override
    public String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end && TRIM.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }
}
