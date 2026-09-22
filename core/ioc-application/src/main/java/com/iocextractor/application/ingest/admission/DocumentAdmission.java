package com.iocextractor.application.ingest.admission;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.RegisteredObservation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable aggregate for one document occurrence before the existing ingest ledger takes over. */
public record DocumentAdmission(ObservationId observationId,
                                Path candidatePath,
                                DocumentCandidateEvidence candidateEvidence,
                                Path claimPath,
                                Optional<DocumentCandidateEvidence> claimedEvidence,
                                DocumentAdmissionPhase phase,
                                long version,
                                Optional<RegisteredObservation> registration,
                                Optional<SourceKey> sourceKey,
                                Optional<DocumentTerminalOutcome> terminalOutcome,
                                boolean registrationFinalized,
                                Instant createdAt,
                                Instant updatedAt) {

    public DocumentAdmission {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(candidatePath, "candidatePath");
        Objects.requireNonNull(candidateEvidence, "candidateEvidence");
        Objects.requireNonNull(claimPath, "claimPath");
        claimedEvidence = Objects.requireNonNull(claimedEvidence, "claimedEvidence");
        Objects.requireNonNull(phase, "phase");
        registration = Objects.requireNonNull(registration, "registration");
        sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
        terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid document admission version or timestamps");
        }
        if ((phase.ordinal() >= DocumentAdmissionPhase.ORDERED.ordinal()) != registration.isPresent()) {
            throw new IllegalArgumentException("Ordered document admission requires one registration");
        }
        if ((phase.ordinal() >= DocumentAdmissionPhase.CLAIMED.ordinal()) != claimedEvidence.isPresent()) {
            throw new IllegalArgumentException("Claimed document admission requires claimed evidence");
        }
        if ((phase.ordinal() >= DocumentAdmissionPhase.LINKED.ordinal()) != sourceKey.isPresent()) {
            throw new IllegalArgumentException("Linked document admission requires a source key");
        }
        if ((phase == DocumentAdmissionPhase.TERMINAL) != terminalOutcome.isPresent()) {
            throw new IllegalArgumentException("Terminal outcome must exist exactly in terminal phase");
        }
        if (registrationFinalized && phase != DocumentAdmissionPhase.TERMINAL) {
            throw new IllegalArgumentException("Only terminal admission can finalize registration");
        }
    }

    public static DocumentAdmission reserved(DocumentAdmissionReservation reservation) {
        return new DocumentAdmission(reservation.observationId(), reservation.candidatePath(),
                reservation.candidateEvidence(), reservation.claimPath(), Optional.empty(),
                DocumentAdmissionPhase.RESERVED, 0, Optional.empty(), Optional.empty(),
                Optional.empty(), false, reservation.detectedAt(), reservation.detectedAt());
    }

    public DocumentAdmission ordered(RegisteredObservation value, Instant at) {
        requirePhase(DocumentAdmissionPhase.RESERVED);
        if (!value.observationId().equals(observationId)) {
            throw new IllegalArgumentException("Registration belongs to another document occurrence");
        }
        return copy(DocumentAdmissionPhase.ORDERED, Optional.of(value), Optional.empty(),
                Optional.empty(), Optional.empty(), false, at);
    }

    public DocumentAdmission claimed(DocumentCandidateEvidence evidence, Instant at) {
        requirePhase(DocumentAdmissionPhase.ORDERED);
        if (!candidateEvidence.sameObjectAs(evidence)) {
            throw new IllegalStateException("Claimed document evidence differs from reservation");
        }
        return copy(DocumentAdmissionPhase.CLAIMED, registration, Optional.of(evidence),
                Optional.empty(), Optional.empty(), false, at);
    }

    public DocumentAdmission linked(SourceKey value, Instant at) {
        requirePhase(DocumentAdmissionPhase.CLAIMED);
        return copy(DocumentAdmissionPhase.LINKED, registration, claimedEvidence,
                Optional.of(Objects.requireNonNull(value, "sourceKey")), Optional.empty(), false, at);
    }

    public DocumentAdmission terminal(DocumentTerminalOutcome outcome, Instant at) {
        if (phase != DocumentAdmissionPhase.LINKED) {
            throw new IllegalStateException("Document admission must be linked before terminal disposition");
        }
        return copy(DocumentAdmissionPhase.TERMINAL, registration, claimedEvidence, sourceKey,
                Optional.of(Objects.requireNonNull(outcome, "outcome")), false, at);
    }

    public DocumentAdmission registrationFinalized(Instant at) {
        requirePhase(DocumentAdmissionPhase.TERMINAL);
        return copy(phase, registration, claimedEvidence, sourceKey, terminalOutcome, true, at);
    }

    private DocumentAdmission copy(DocumentAdmissionPhase next,
                                   Optional<RegisteredObservation> nextRegistration,
                                   Optional<DocumentCandidateEvidence> nextClaimedEvidence,
                                   Optional<SourceKey> nextSourceKey,
                                   Optional<DocumentTerminalOutcome> nextOutcome,
                                   boolean finalized,
                                   Instant at) {
        return new DocumentAdmission(observationId, candidatePath, candidateEvidence, claimPath,
                nextClaimedEvidence, next, version + 1, nextRegistration, nextSourceKey,
                nextOutcome, finalized, createdAt, Objects.requireNonNull(at, "at"));
    }

    private void requirePhase(DocumentAdmissionPhase expected) {
        if (phase != expected) {
            throw new IllegalStateException("Expected document admission phase " + expected + " but was " + phase);
        }
    }
}
