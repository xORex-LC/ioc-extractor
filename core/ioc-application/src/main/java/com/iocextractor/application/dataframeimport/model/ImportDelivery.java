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
 * @param replayOf terminal occurrence that caused this replay, when present
 * @param state current recovery state
 * @param version optimistic-CAS version
 * @param evidence immutable snapshot, contract and stage checkpoints
 * @param retry retry counters, eligibility and bounded safe code
 * @param terminalOutcome outcome present only in terminal state
 * @param createdAt durable claim reservation time
 * @param updatedAt last durable transition time
 */
public record ImportDelivery(
        ImportDeliveryId id,
        ImportDeliverySequence sequence,
        ImportSourceId sourceId,
        String candidateToken,
        Optional<ImportDeliveryId> replayOf,
        ImportDeliveryState state,
        long version,
        ImportDeliveryEvidence evidence,
        ImportDeliveryRetryState retry,
        Optional<ImportTerminalOutcome> terminalOutcome,
        Instant createdAt,
        Instant updatedAt) {

    /** Enforces aggregate state and terminal-outcome invariants. */
    public ImportDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(candidateToken, "candidateToken");
        replayOf = Objects.requireNonNull(replayOf, "replayOf");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(retry, "retry");
        terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        Objects.requireNonNull(createdAt, "createdAt");
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
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Import delivery update time must not precede creation");
        }
    }

    /** Returns pinned snapshot evidence when present. */
    public Optional<ImportSnapshot> snapshot() {
        return evidence.snapshot();
    }

    /** Returns pinned exact-one contract evidence when present. */
    public Optional<ImportContractPin> contract() {
        return evidence.contract();
    }

    /** Returns sealed staging evidence when present. */
    public Optional<ImportStage> stage() {
        return evidence.stage();
    }

    /** Returns actual failed processing attempts. */
    public int attemptCount() {
        return retry.attemptCount();
    }

    /** Returns retry eligibility when deferred. */
    public Optional<Instant> nextAttemptAt() {
        return retry.nextAttemptAt();
    }

    /** Returns the latest bounded safe retry/transition code. */
    public Optional<String> lastErrorCode() {
        return retry.lastErrorCode();
    }
}
