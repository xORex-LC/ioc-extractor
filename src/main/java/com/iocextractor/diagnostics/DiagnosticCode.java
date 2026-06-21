package com.iocextractor.diagnostics;

/**
 * Stable catalog entry identifying one diagnostic condition.
 *
 * <p>Implementations are expected to be enumerable catalogs, usually enums.
 * The {@link #id()} value is a public contract used in reports, generated
 * documentation and future logging bridges.
 */
public interface DiagnosticCode {

    /**
     * Returns a stable id in {@code GROUP.CODE} form.
     *
     * @return stable diagnostic id
     */
    String id();

    /**
     * Returns the processing category for this diagnostic.
     *
     * @return diagnostic category
     */
    DiagnosticCategory category();

    /**
     * Returns the default severity used when a producer does not override it.
     *
     * @return default severity
     */
    DiagnosticSeverity defaultSeverity();

    /**
     * Returns a stable message key for future catalog/bundle lookup.
     *
     * @return stable message key
     */
    String messageKey();

    /**
     * Returns a self-contained fallback template for rendering this diagnostic.
     *
     * @return default message template
     */
    String defaultMessageTemplate();
}
