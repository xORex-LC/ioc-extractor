package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Durable outcome of one lifecycle-aware canonical artifact transaction.
 *
 * <p>{@code created}, {@code renewed} and {@code restarted} are mutually
 * exclusive row dispositions. Created and restarted rows are new public active
 * rows and therefore advance the insert-driven artifact revision. Renewal does
 * not.
 *
 * @param observationId observation committed or replayed
 * @param artifactName affected artifact
 * @param effectiveTime transaction-level confirmation time
 * @param created records with no active or due canonical predecessor
 * @param renewed still-active records confirmed in place
 * @param restarted due records closed and recreated with new identities
 * @param artifactRevision insert-driven revision observed after the transaction
 * @param requiredProjectionGeneration mutable-projection generation observed after the transaction
 * @param replayed whether an existing observation commit marker supplied this outcome
 */
public record LifecycleWriteResult(ObservationId observationId,
                                   String artifactName,
                                   EffectiveTime effectiveTime,
                                   int created,
                                   int renewed,
                                   int restarted,
                                   long artifactRevision,
                                   ProjectionGeneration requiredProjectionGeneration,
                                   boolean replayed) {

    /** Validates counts, identity and revision invariants. */
    public LifecycleWriteResult {
        Objects.requireNonNull(observationId, "observationId");
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        Objects.requireNonNull(requiredProjectionGeneration, "requiredProjectionGeneration");
        if (created < 0 || renewed < 0 || restarted < 0) {
            throw new IllegalArgumentException("Lifecycle write counts must not be negative");
        }
        if (artifactRevision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (newPublicRows(created, restarted) > 0 && artifactRevision == 0) {
            throw new IllegalArgumentException("New public rows require a positive artifact revision");
        }
    }

    /** Returns the number of public rows inserted into active storage. */
    public int publicRowsInserted() {
        return newPublicRows(created, restarted);
    }

    /** Returns the number of prepared records classified by the transaction. */
    public int confirmedRecords() {
        return Math.addExact(publicRowsInserted(), renewed);
    }

    private static int newPublicRows(int created, int restarted) {
        return Math.addExact(created, restarted);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
