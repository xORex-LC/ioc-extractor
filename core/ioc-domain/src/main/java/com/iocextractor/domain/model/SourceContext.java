package com.iocextractor.domain.model;

/**
 * Provenance attached to an indicator: which section header it appeared under.
 *
 * @param label   the {@code source} value (e.g. "Письмо ФСТЭК России от … № …"),
 *                or {@code null} when no section marker preceded the indicator
 * @param section optional finer-grained sub-section title (may be {@code null})
 */
public record SourceContext(String label, String section) {

    public SourceContext withLabel(String newLabel) {
        return new SourceContext(newLabel, section);
    }
}
