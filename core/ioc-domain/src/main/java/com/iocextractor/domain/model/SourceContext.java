package com.iocextractor.domain.model;

/**
 * Provenance attached to an indicator: which section header it appeared under.
 *
 * @param label   the {@code source} value (e.g. "Письмо ФСТЭК России от … № …")
 * @param section optional finer-grained sub-section title (may be {@code null})
 */
public record SourceContext(String label, String section) {

    public static final SourceContext UNKNOWN = new SourceContext("UNKNOWN", null);

    public SourceContext withLabel(String newLabel) {
        return new SourceContext(newLabel, section);
    }
}
