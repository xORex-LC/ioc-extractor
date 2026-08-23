package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.in.artifact.lifecycle.ConvergeArtifactProjectionsUseCase;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Converges durable mutable-projection generations through the existing sink port. */
public final class ArtifactProjectionConvergenceService implements ConvergeArtifactProjectionsUseCase {

    public static final String PROJECTION_FAILURE = "LIFECYCLE.PROJECTION_FAILED";

    private final List<String> artifacts;
    private final ArtifactProjectionWorkStore work;
    private final ArtifactProjection projection;

    public ArtifactProjectionConvergenceService(List<String> artifacts,
                                                ArtifactProjectionWorkStore work,
                                                ArtifactProjection projection) {
        this.artifacts = requireArtifacts(artifacts);
        this.work = Objects.requireNonNull(work, "work");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public ArtifactProjectionConvergenceResult convergePending() {
        List<String> projected = new ArrayList<>();
        int stillPending = 0;
        RuntimeException firstFailure = null;
        for (String artifact : artifacts) {
            ArtifactProjectionState state = work.load(artifact);
            if (!state.pending()) {
                continue;
            }
            try {
                projection.project(new ArtifactProjectionCommand(operationId(state), artifact));
                boolean acknowledged = work.acknowledge(new ProjectionAcknowledgement(
                        artifact, state.requiredGeneration(), state.requiredGeneration()));
                if (acknowledged) {
                    projected.add(artifact);
                } else {
                    stillPending++;
                }
            } catch (RuntimeException failure) {
                recordFailure(artifact, state.requiredGeneration(), failure);
                stillPending++;
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return new ArtifactProjectionConvergenceResult(projected.size(), stillPending, projected);
    }

    private void recordFailure(String artifact,
                               ProjectionGeneration generation,
                               RuntimeException primaryFailure) {
        try {
            work.recordFailure(artifact, generation, PROJECTION_FAILURE);
        } catch (RuntimeException journalFailure) {
            primaryFailure.addSuppressed(journalFailure);
        }
    }

    private String operationId(ArtifactProjectionState state) {
        return "lifecycle-projection-" + state.artifactName() + "-g" + state.requiredGeneration().value();
    }

    private static List<String> requireArtifacts(List<String> source) {
        Objects.requireNonNull(source, "artifacts");
        Set<String> unique = new LinkedHashSet<>();
        for (String artifact : source) {
            if (artifact == null || artifact.isBlank() || !unique.add(artifact)) {
                throw new IllegalArgumentException("Artifact catalog must contain unique non-blank names");
            }
        }
        return List.copyOf(source);
    }
}
