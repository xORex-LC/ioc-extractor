package com.iocextractor.application.dataframeimport.contract;

import java.util.Objects;

/**
 * Safe collect-all semantic catalog violation.
 *
 * @param path stable configuration path below {@code ioc.dataframe-import}
 * @param message value-free corrective message
 */
public record ImportContractViolation(String path, String message) {

    /** Enforces usable safe diagnostic fields. */
    public ImportContractViolation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        if (path.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("Import contract violation path/message must not be blank");
        }
    }
}
