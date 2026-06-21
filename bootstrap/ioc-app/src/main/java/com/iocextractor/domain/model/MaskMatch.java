package com.iocextractor.domain.model;

/**
 * Matching codes for a network mask, mirroring the reference artifact columns
 * {@code url_match} / {@code host_match} (e.g. "u:hAS" / "h:dAS").
 * A {@code null} field is rendered as the CSV null literal.
 */
public record MaskMatch(String urlMatch, String hostMatch) {
}
