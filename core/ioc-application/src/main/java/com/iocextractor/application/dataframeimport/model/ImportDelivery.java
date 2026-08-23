package com.iocextractor.application.dataframeimport.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact durable delivery aggregate. Bulk row values remain in protected files.
 *
 * @param id occurrence identity
 * @param sequence global claim sequence
 * @param sourceId source trust boundary
 * @param candidateToken adapter-stable candidate token
 * @param state current recovery state
 * @param version optimistic-CAS version
 * @param terminalOutcome outcome present only in terminal state
 * @param updatedAt last durable transition time
 */
public record ImportDelivery(
        ImportDeliveryId id,
        ImportDeliverySequence sequence,
        ImportSourceId sourceId,
        String candidateToken,
        ImportDeliveryState state,
        long version,
        Optional<ImportTerminalOutcome> terminalOutcome,
        Instant updatedAt) {

    /** Enforces aggregate state and terminal-outcome invariants. */
    public ImportDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(candidateToken, "candidateToken");
        Objects.requireNonNull(state, "state");
        terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (candidateToken.isBlank()) {
            throw new IllegalArgumentException("Import candidate token must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Import delivery version must not be negative");
        }
        if ((state == ImportDeliveryState.TERMINAL) != terminalOutcome.isPresent()) {
            throw new IllegalArgumentException("Terminal outcome must be present exactly in TERMINAL state");
        }
    }
}
