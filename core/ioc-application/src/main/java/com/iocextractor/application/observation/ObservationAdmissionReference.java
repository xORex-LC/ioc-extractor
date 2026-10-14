package com.iocextractor.application.observation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Service-ledger reference used to recover a registration without allocating a new rank. */
public record ObservationAdmissionReference(RegisteredObservation registration,
                                            long version,
                                            Optional<String> terminalOutcome,
                                            boolean registrationFinalized,
                                            Instant createdAt,
                                            Instant updatedAt) {

    public ObservationAdmissionReference {
        Objects.requireNonNull(registration, "registration");
        terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome")
                .filter(value -> !value.isBlank());
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid observation reference version or timestamps");
        }
        if (registrationFinalized && terminalOutcome.isEmpty()) {
            throw new IllegalArgumentException("Finalized observation reference must be terminal");
        }
    }

    public ObservationAdmissionReference terminal(String outcome, Instant at) {
        String value = Objects.requireNonNull(outcome, "outcome");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Terminal outcome must not be blank");
        }
        if (terminalOutcome.isPresent() && !terminalOutcome.orElseThrow().equals(value)) {
            throw new IllegalStateException("Observation terminal outcome changed on retry");
        }
        return new ObservationAdmissionReference(registration, version + 1, Optional.of(value),
                registrationFinalized, createdAt, Objects.requireNonNull(at, "at"));
    }

    public ObservationAdmissionReference finalized(Instant at) {
        if (terminalOutcome.isEmpty()) {
            throw new IllegalStateException("Observation reference is not terminal");
        }
        return new ObservationAdmissionReference(registration, version + 1, terminalOutcome,
                true, createdAt, Objects.requireNonNull(at, "at"));
    }
}
