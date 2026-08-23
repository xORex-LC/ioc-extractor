package com.iocextractor.application.dataframeimport.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Compare-and-set transition request for a durable delivery aggregate.
 *
 * @param deliveryId delivery to update
 * @param expectedState required current state
 * @param expectedVersion required current version
 * @param nextState forward state
 * @param terminalOutcome outcome only when entering terminal state
 * @param occurredAt transition time
 */
public record ImportDeliveryTransition(
        ImportDeliveryId deliveryId,
        ImportDeliveryState expectedState,
        long expectedVersion,
        ImportDeliveryState nextState,
        Optional<ImportTerminalOutcome> terminalOutcome,
        Instant occurredAt) {

    /** Enforces CAS and terminal fields; transition legality belongs to the ledger contract. */
    public ImportDeliveryTransition {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(nextState, "nextState");
        terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected delivery version must not be negative");
        }
        if ((nextState == ImportDeliveryState.TERMINAL) != terminalOutcome.isPresent()) {
            throw new IllegalArgumentException("Terminal outcome must accompany only a TERMINAL transition");
        }
    }
}
