package com.iocextractor.domain.feature;

/**
 * Cleans a matched indicator value into a canonical form (e.g. strips trailing
 * punctuation that bleeds in from surrounding text). Pure function, no I/O.
 */
public interface IndicatorNormalizer {

    String normalize(String value);
}
