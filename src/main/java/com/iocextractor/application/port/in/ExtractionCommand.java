package com.iocextractor.application.port.in;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Inbound request for one extraction run.
 *
 * @param source path to the source document (.htm/.docx/.pdf/…)
 * @param dryRun when {@code true}, extract & report but do not write artifacts
 */
public record ExtractionCommand(Path source, boolean dryRun) {

    public ExtractionCommand {
        Objects.requireNonNull(source, "source");
    }

    public static ExtractionCommand of(Path source) {
        return new ExtractionCommand(source, false);
    }
}
