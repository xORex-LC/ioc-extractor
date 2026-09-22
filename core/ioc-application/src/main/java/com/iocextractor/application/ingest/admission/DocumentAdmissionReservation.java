package com.iocextractor.application.ingest.admission;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Stable reservation persisted before dataframe order allocation. */
public record DocumentAdmissionReservation(ObservationId observationId,
                                           Path candidatePath,
                                           DocumentCandidateEvidence candidateEvidence,
                                           Path claimPath,
                                           Instant detectedAt) {

    public DocumentAdmissionReservation {
        Objects.requireNonNull(observationId, "observationId");
        candidatePath = normalize(candidatePath, "candidatePath");
        Objects.requireNonNull(candidateEvidence, "candidateEvidence");
        claimPath = normalize(claimPath, "claimPath");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (candidatePath.equals(claimPath)) {
            throw new IllegalArgumentException("Document claim path must differ from candidate path");
        }
    }

    private static Path normalize(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
