package com.iocextractor.application.pipeline;

import java.util.Objects;

/**
 * Generic, stable identifier of a pipeline stage.
 *
 * <p>Intentionally free of IOC-specific vocabulary so the ETL kernel can be
 * reused across pipelines. Concrete stage identifiers (e.g. {@code READ_SOURCE})
 * are declared by the application that builds the pipeline.
 *
 * @param value stable machine-readable stage id
 */
public record StageId(String value) {

    /** Identifier for the initial, pre-stage envelope. */
    public static final StageId INITIAL = new StageId("INITIAL");

    /**
     * Creates a stage id.
     *
     * @param value non-blank stable id
     */
    public StageId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("stage id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
