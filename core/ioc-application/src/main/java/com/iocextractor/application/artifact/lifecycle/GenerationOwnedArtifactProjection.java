package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.Diagnostic;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single process-local owner of mutable installation and acknowledgement per configured artifact.
 *
 * <p>A fair interruptible artifact lock spans snapshot/build/install/ack. Building does not
 * own canonical writer admission; mutations can advance required work while it runs.
 * Lock order is artifact owner then short database admission, never the reverse. The caller
 * owns execution and cancellation; this service creates no executor or unbounded work queue.
 * Separate processes must not share mutable output paths.</p>
 */
public final class GenerationOwnedArtifactProjection implements ArtifactProjection {
    private final Map<String, Owner> owners;
    private final ArtifactProjection installer;
    private final ArtifactProjectionWorkStore work;

    /** Creates fixed-cardinality owners; the raw installer must not be exposed to other callers. */
    public GenerationOwnedArtifactProjection(List<String> artifacts, ArtifactProjection installer,
                                             ArtifactProjectionWorkStore work) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.work = Objects.requireNonNull(work, "work");
        Map<String, Owner> catalog = new LinkedHashMap<>();
        for (String artifact : Objects.requireNonNull(artifacts, "artifacts")) {
            if (artifact == null || artifact.isBlank() || catalog.put(artifact, new Owner()) != null) {
                throw new IllegalArgumentException("Artifact catalog must contain unique non-blank names");
            }
        }
        this.owners = Map.copyOf(catalog);
    }

    @Override
    public ArtifactProjectionResult project(ArtifactProjectionCommand request) {
        Objects.requireNonNull(request, "request");
        Owner owner = owners.get(request.artifactName());
        if (owner == null) {
            throw new IllegalArgumentException("Unknown projection artifact: " + request.artifactName());
        }
        try {
            owner.lock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Interrupted while waiting for artifact projection ownership", failure);
        }
        try {
            return projectOwned(owner, request);
        } finally {
            owner.lock.unlock();
        }
    }

    private ArtifactProjectionResult projectOwned(Owner owner, ArtifactProjectionCommand request) {
        ArtifactProjectionState state = work.load(request.artifactName());
        if (owner.completed != null && state.requiredGeneration().value() > 0
                && !state.pending()
                && owner.completed.installedGeneration().equals(state.projectedGeneration())) {
            return correlate(owner.completed, request.runId());
        }
        owner.completed = null;
        ProjectionGeneration attemptedGeneration = state.requiredGeneration();
        try {
            checkCancellation();
            ArtifactProjectionResult result = installer.project(request);
            if (result.installedGeneration().compareTo(state.requiredGeneration()) < 0) {
                throw new IocExtractorException("Installed projection does not cover requested generation");
            }
            attemptedGeneration = result.installedGeneration();
            checkCancellation();
            if (result.installedGeneration().value() > 0
                    && !work.acknowledge(new ProjectionAcknowledgement(
                            request.artifactName(), result.installedGeneration()))) {
                throw new IocExtractorException("Installed projection coverage was not acknowledged");
            }
            owner.completed = result;
            return result;
        } catch (RuntimeException failure) {
            try {
                work.recordFailure(request.artifactName(), attemptedGeneration,
                        ArtifactProjectionConvergenceService.PROJECTION_FAILURE);
            } catch (RuntimeException journalFailure) {
                failure.addSuppressed(journalFailure);
            }
            throw failure;
        }
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IocExtractorException("Artifact projection cancelled",
                    new InterruptedException("Projection caller interrupted"));
        }
    }

    private static ArtifactProjectionResult correlate(ArtifactProjectionResult result, String runId) {
        List<Diagnostic> diagnostics = result.diagnostics().stream().map(diagnostic -> {
            var builder = Diagnostic.builder(diagnostic.code(), Clock.fixed(diagnostic.timestamp(), ZoneOffset.UTC))
                    .severity(diagnostic.severity()).context(diagnostic.context()).with("runId", runId);
            diagnostic.cause().ifPresent(builder::cause);
            return builder.build();
        }).toList();
        return new ArtifactProjectionResult(result.projectedRows(), diagnostics, result.installedGeneration());
    }

    private static final class Owner {
        private final ReentrantLock lock = new ReentrantLock(true);
        // Accessed only under this artifact's lock.
        private ArtifactProjectionResult completed;
    }
}
