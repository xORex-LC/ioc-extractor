package com.iocextractor.application.port.in;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Inbound request for one extraction run.
 *
 * @param runId correlation identifier supplied by the driving boundary
 * @param source path to the source document (.htm/.docx/.pdf/…)
 * @param dryRun when {@code true}, extract & report but do not write artifacts
 */
public record ExtractionCommand(String runId, Path source, boolean dryRun) {

    public ExtractionCommand {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(source, "source");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }
}
