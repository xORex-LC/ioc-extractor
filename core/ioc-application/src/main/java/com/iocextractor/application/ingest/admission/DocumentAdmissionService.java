package com.iocextractor.application.ingest.admission;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.ObservationRegistrationPurgeOutcome;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.ingest.DocumentAdmissionJournal;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Coordinates short local transactions without holding service and dataframe locks together. */
public final class DocumentAdmissionService {

    private final DocumentAdmissionJournal journal;
    private final ObservationRegistrationStore registrations;
    private final Clock clock;

    public DocumentAdmissionService(DocumentAdmissionJournal journal,
                                    ObservationRegistrationStore registrations,
                                    Clock clock) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DocumentAdmission admit(DocumentAdmissionReservation reservation) {
        DocumentAdmission current = journal.reserve(reservation);
        if (current.phase() != DocumentAdmissionPhase.RESERVED) {
            return verifyRegistration(current);
        }
        RegisteredObservation registration = registrations.registerNew(
                current.observationId(), ObservationOrigin.DOCUMENT);
        return advance(current, current.ordered(registration, clock.instant()));
    }

    /** Returns durable admission state so an adapter retry can resume after ownership moved. */
    public Optional<DocumentAdmission> find(ObservationId observationId) {
        return journal.find(Objects.requireNonNull(observationId, "observationId"));
    }

    public DocumentAdmission recordClaim(ObservationId id, DocumentCandidateEvidence evidence) {
        DocumentAdmission current = required(id);
        verifyRegistration(current);
        return advance(current, current.claimed(evidence, clock.instant()));
    }

    public DocumentAdmission link(ObservationId id, SourceKey key) {
        DocumentAdmission current = required(id);
        verifyRegistration(current);
        return advance(current, current.linked(key, clock.instant()));
    }

    /** Resumes the exact durable registration linked to a document occurrence. */
    public RegisteredObservation resume(ObservationId id) {
        return verifyRegistration(required(id)).registration().orElseThrow();
    }

    public DocumentAdmission complete(ObservationId id, DocumentTerminalOutcome outcome) {
        DocumentAdmission current = required(id);
        if (current.phase() != DocumentAdmissionPhase.TERMINAL) {
            current = advance(current, current.terminal(outcome, clock.instant()));
        } else if (current.terminalOutcome().orElseThrow() != outcome) {
            throw new IllegalStateException("Document terminal outcome changed during retry");
        }
        return finalizeRegistration(current);
    }

    public List<DocumentAdmission> recover(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Document admission recovery limit must be positive");
        }
        return journal.findRecoverable(limit).stream().map(this::recoverOne).toList();
    }

    public int purgeTerminalBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1) {
            throw new IllegalArgumentException("Document admission purge limit must be positive");
        }
        int purged = 0;
        for (DocumentAdmission admission : journal.findTerminalBefore(cutoff, limit)) {
            if (!admission.registrationFinalized()) {
                continue;
            }
            ObservationRegistrationPurgeOutcome outcome = registrations.purgeTerminalSafely(
                    admission.registration().orElseThrow());
            if (outcome != ObservationRegistrationPurgeOutcome.REFERENCED
                    && journal.purgeTerminal(admission.observationId(), admission.version())) {
                purged++;
            }
        }
        return purged;
    }

    private DocumentAdmission recoverOne(DocumentAdmission current) {
        if (current.phase() == DocumentAdmissionPhase.RESERVED) {
            RegisteredObservation registration = registrations.registerNew(
                    current.observationId(), ObservationOrigin.DOCUMENT);
            return advance(current, current.ordered(registration, clock.instant()));
        }
        verifyRegistration(current);
        return current.phase() == DocumentAdmissionPhase.TERMINAL
                ? finalizeRegistration(current) : current;
    }

    private DocumentAdmission verifyRegistration(DocumentAdmission admission) {
        RegisteredObservation expected = admission.registration().orElseThrow(
                () -> new IllegalStateException("Document admission has no registration reference"));
        RegisteredObservation actual = registrations.resume(
                admission.observationId(), expected.namespaceId());
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Document admission registration reference changed");
        }
        return admission;
    }

    private DocumentAdmission finalizeRegistration(DocumentAdmission current) {
        if (current.registrationFinalized()) {
            return current;
        }
        RegisteredObservation registration = current.registration().orElseThrow();
        registrations.markTerminal(current.observationId(), registration.namespaceId());
        return advance(current, current.registrationFinalized(clock.instant()));
    }

    private DocumentAdmission required(ObservationId id) {
        return journal.find(Objects.requireNonNull(id, "observationId")).orElseThrow(
                () -> new IllegalStateException("Missing document admission"));
    }

    private DocumentAdmission advance(DocumentAdmission current, DocumentAdmission updated) {
        if (!journal.replace(current, updated)) {
            DocumentAdmission durable = required(current.observationId());
            if (durable.equals(updated)) {
                return durable;
            }
            throw new IllegalStateException("Document admission changed concurrently");
        }
        return updated;
    }
}
