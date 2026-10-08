package com.iocextractor.domain.extract;

/**
 * Detects indicators in a refanged document. Implementations are responsible
 * for resolving overlaps by type priority (e.g. a URL host is not re-emitted
 * as a bare domain).
 */
public interface IndicatorExtractor {

    /** Extracts indicators and exposes accepted and dropped match decisions. */
    ExtractionOutcome extract(String text);

    /** Streams priority-ordered decisions into admitted global overlap state. */
    default void extract(CharSequence text, int maximumMatchCharacters, ExtractionClaims claims) {
        throw new UnsupportedOperationException("Streaming extraction is required for document processing");
    }
}
