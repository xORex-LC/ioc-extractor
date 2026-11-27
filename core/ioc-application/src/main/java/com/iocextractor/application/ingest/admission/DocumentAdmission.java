package com.iocextractor.application.ingest.admission;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.RegisteredObservation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable identity, ownership and execution state for one document occurrence.
 *
 * @param claimedEvidence file identity of the private claim, updated to the sealed snapshot before hashing
 * @param rejection immutable physical disposition proof retained through terminal registration
 */
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
                                Instant updatedAt,
                                DocumentExecutionState execution,
                                Optional<DocumentRejection> rejection) {

    public DocumentAdmission {
        Objects.requireNonNull(execution, "execution");
        rejection = Objects.requireNonNull(rejection, "rejection");
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
        if (phase == DocumentAdmissionPhase.LINKED && sourceKey.isEmpty()
                || phase.ordinal() < DocumentAdmissionPhase.LINKED.ordinal() && sourceKey.isPresent()) {
            throw new IllegalArgumentException("Linked document admission requires a source key");
        }
        if ((phase == DocumentAdmissionPhase.TERMINAL) != terminalOutcome.isPresent()) {
            throw new IllegalArgumentException("Terminal outcome must exist exactly in terminal phase");
        }
        if (rejection.isPresent() && (claimedEvidence.isEmpty()
                    || terminalOutcome.orElse(DocumentTerminalOutcome.REJECTED) != DocumentTerminalOutcome.REJECTED)
                || phase == DocumentAdmissionPhase.TERMINAL && sourceKey.isEmpty() && rejection.isEmpty()) {
            throw new IllegalArgumentException("Invalid document rejection state");
        }
        if (registrationFinalized && phase != DocumentAdmissionPhase.TERMINAL) {
            throw new IllegalArgumentException("Only terminal admission can finalize registration");
        }
    }

    public DocumentAdmission(ObservationId observationId, Path candidatePath,
            DocumentCandidateEvidence candidateEvidence, Path claimPath,
            Optional<DocumentCandidateEvidence> claimedEvidence, DocumentAdmissionPhase phase, long version,
            Optional<RegisteredObservation> registration, Optional<SourceKey> sourceKey,
            Optional<DocumentTerminalOutcome> terminalOutcome, boolean registrationFinalized,
            Instant createdAt, Instant updatedAt) {
        this(observationId, candidatePath, candidateEvidence, claimPath, claimedEvidence, phase,
                version, registration, sourceKey, terminalOutcome, registrationFinalized,
                createdAt, updatedAt, DocumentExecutionState.NEW, Optional.empty());
    }

    public DocumentAdmission execution(DocumentExecutionState state, Instant at) {
        return new DocumentAdmission(observationId, candidatePath, candidateEvidence, claimPath,
                claimedEvidence, phase, version + 1, registration, sourceKey, terminalOutcome,
                registrationFinalized, createdAt, coordinationTime(at), state, rejection);
    }

    /** Captures the private snapshot's file identity before hashing its bytes. */
    public DocumentAdmission snapshot(DocumentCandidateEvidence evidence, Instant at) {
        requirePhase(DocumentAdmissionPhase.CLAIMED);
        return copy(phase, registration, Optional.of(evidence), sourceKey, terminalOutcome, false, at);
    }

    /** Persists the chosen file and target before any filesystem disposition. */
    public DocumentAdmission reject(DocumentRejection intent, Instant at) {
        if (phase != DocumentAdmissionPhase.CLAIMED && phase != DocumentAdmissionPhase.LINKED
                || rejection.isPresent()) {
            throw new IllegalStateException("Document cannot begin rejection");
        }
        return new DocumentAdmission(observationId, candidatePath, candidateEvidence, claimPath,
                claimedEvidence, phase, version + 1, registration, sourceKey, terminalOutcome,
                false, createdAt, coordinationTime(at), execution, Optional.of(intent));
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
        if (rejection.isPresent()) {
            throw new IllegalStateException("Rejecting document cannot be linked");
        }
        return copy(DocumentAdmissionPhase.LINKED, registration, claimedEvidence,
                Optional.of(Objects.requireNonNull(value, "sourceKey")), Optional.empty(), false, at);
    }

    public DocumentAdmission terminal(DocumentTerminalOutcome outcome, Instant at) {
        boolean allowed = switch (outcome) {
            case SUCCEEDED, QUARANTINED -> phase == DocumentAdmissionPhase.LINKED && rejection.isEmpty();
            case REJECTED -> rejection.isPresent() && phase != DocumentAdmissionPhase.TERMINAL;
        };
        if (!allowed) {
            throw new IllegalStateException("Document has no eligible terminal disposition");
        }
        return copy(DocumentAdmissionPhase.TERMINAL, registration, claimedEvidence, sourceKey,
                Optional.of(outcome), false, at);
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
                nextOutcome, finalized, createdAt, coordinationTime(at), execution, rejection);
    }

    /** Operational progress stays ordered when wall UTC steps back; deadlines retain their own UTC authority. */
    private Instant coordinationTime(Instant at) {
        Objects.requireNonNull(at, "at");
        return at.isBefore(updatedAt) ? updatedAt : at;
    }

    private void requirePhase(DocumentAdmissionPhase expected) {
        if (phase != expected) {
            throw new IllegalStateException("Expected document admission phase " + expected + " but was " + phase);
        }
    }
}
