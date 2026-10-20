package com.iocextractor.observability;

import com.iocextractor.platform.logging.redaction.LogRedaction;

/** IOC convenience for the shared lexical URL-component redactor. */
public final class SensitiveLogValueSanitizer {
    private SensitiveLogValueSanitizer() { }

    /**
     * Hides URL user-info and query content before IOC diagnostic rendering.
     * Paths, fragments and scheme-less credentials remain visible. The diagnostic
     * renderer still owns fingerprint and severity-dependent disclosure policy.
     *
     * @param value raw value, possibly null
     * @return value with recognized URL credentials and query content hidden
     */
    public static String sanitize(String value) {
        return LogRedaction.urlComponents(value);
    }
}
