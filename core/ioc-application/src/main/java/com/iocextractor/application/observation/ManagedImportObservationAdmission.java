package com.iocextractor.application.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.port.out.observation.ObservationAdmissionReferenceStore;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Adds dataframe precedence to an existing import delivery ID without reusing import sequence_no. */
public final class ManagedImportObservationAdmission {

    private final ObservationRegistrationStore registrations;
    private final ObservationAdmissionReferenceStore references;
    private final Clock clock;

    public ManagedImportObservationAdmission(ObservationRegistrationStore registrations,
                                             ObservationAdmissionReferenceStore references,
                                             Clock clock) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.references = Objects.requireNonNull(references, "references");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RegisteredObservation register(ImportDeliveryId deliveryId) {
        ObservationId id = observationId(deliveryId);
        RegisteredObservation registration = registrations.registerNew(id, ObservationOrigin.MANAGED_IMPORT);
        ObservationAdmissionReference reference = references.link(registration);
        if (!reference.registration().equals(registration)) {
            throw new IllegalStateException("Import delivery registration reference changed");
        }
        return registration;
    }

    public RegisteredObservation resume(ImportDeliveryId deliveryId) {
        ObservationAdmissionReference reference = required(observationId(deliveryId));
        RegisteredObservation actual = registrations.resume(reference.registration().observationId(),
                reference.registration().namespaceId());
        if (!actual.equals(reference.registration())) {
            throw new IllegalStateException("Import delivery registration reference changed");
        }
        return actual;
    }

    public void complete(ImportDeliveryId deliveryId, String outcome) {
        ObservationAdmissionReference current = required(observationId(deliveryId));
        if (current.terminalOutcome().isEmpty()) {
            current = advance(current, current.terminal(outcome, clock.instant()));
        } else if (!current.terminalOutcome().orElseThrow().equals(outcome)) {
            throw new IllegalStateException("Import terminal outcome changed on retry");
        }
        finalizeRegistration(current);
    }

    public List<ObservationAdmissionReference> recover(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Import observation recovery limit must be positive");
        }
        return references.findUnfinalized(limit).stream().map(reference -> {
            resume(new ImportDeliveryId(reference.registration().observationId().value()));
            return reference.terminalOutcome().isPresent()
                    ? finalizeRegistration(reference) : reference;
        }).toList();
    }

    public int purgeTerminalBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1) {
            throw new IllegalArgumentException("Import observation purge limit must be positive");
        }
        int purged = 0;
        for (ObservationAdmissionReference reference : references.findFinalizedBefore(cutoff, limit)) {
            RegisteredObservation registration = reference.registration();
            if (references.purgeFinalized(registration.observationId(), reference.version())) {
                registrations.purgeTerminal(registration);
                purged++;
            }
        }
        return purged;
    }

    private ObservationAdmissionReference finalizeRegistration(ObservationAdmissionReference current) {
        if (current.registrationFinalized()) {
            return current;
        }
        RegisteredObservation registration = current.registration();
        registrations.markTerminal(registration.observationId(), registration.namespaceId());
        return advance(current, current.finalized(clock.instant()));
    }

    private ObservationAdmissionReference advance(ObservationAdmissionReference current,
                                                  ObservationAdmissionReference updated) {
        if (!references.replace(current, updated)) {
            ObservationAdmissionReference durable = required(current.registration().observationId());
            if (durable.equals(updated)) {
                return durable;
            }
            throw new IllegalStateException("Import observation reference changed concurrently");
        }
        return updated;
    }

    private ObservationAdmissionReference required(ObservationId id) {
        return references.find(id).orElseThrow(
                () -> new IllegalStateException("Missing import observation registration reference"));
    }

    private ObservationId observationId(ImportDeliveryId deliveryId) {
        return new ObservationId(Objects.requireNonNull(deliveryId, "deliveryId").value());
    }
}
