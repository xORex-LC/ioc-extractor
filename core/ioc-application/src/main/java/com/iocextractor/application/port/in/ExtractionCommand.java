package com.iocextractor.application.port.in;

import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
import com.iocextractor.application.observation.RegisteredObservation;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbound request for one extraction run.
 *
 * @param runId correlation identifier supplied by the driving boundary
 * @param source path to the source document (.htm/.docx/.pdf/…)
 * @param dryRun when {@code true}, extract & report but do not write artifacts
 * @param lifecycleWriteContext accepted-delivery facts for lifecycle-aware writes, or {@code null}
 * @param registration durable delivery precedence, or {@code null} for legacy artifact policies
 */
public record ExtractionCommand(String runId,
                                Path source,
                                boolean dryRun,
                                LifecycleWriteContext lifecycleWriteContext,
                                RegisteredObservation registration) {

    public ExtractionCommand {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(source, "source");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }

    public ExtractionCommand(String runId, Path source, boolean dryRun,
                             LifecycleWriteContext lifecycleWriteContext) {
        this(runId, source, dryRun, lifecycleWriteContext, null);
    }

    /** Creates the compatibility command that writes through the legacy canonical path. */
    public ExtractionCommand(String runId, Path source, boolean dryRun) {
        this(runId, source, dryRun, null, null);
    }

    /** Returns lifecycle confirmation facts when fixed validity is admitted. */
    public Optional<LifecycleWriteContext> lifecycleWriteContextOptional() {
        return Optional.ofNullable(lifecycleWriteContext);
    }

    /** Returns durable delivery precedence when ordered field policies are active. */
    public Optional<RegisteredObservation> registrationOptional() {
        return Optional.ofNullable(registration);
    }
}
