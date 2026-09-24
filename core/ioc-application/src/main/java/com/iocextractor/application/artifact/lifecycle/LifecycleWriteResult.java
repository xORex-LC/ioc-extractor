package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Durable outcome of one lifecycle-aware canonical artifact transaction.
 *
 * <p>{@code created}, {@code renewed}, {@code restarted} and
 * {@code publicRowsUpdated} are mutually exclusive row dispositions. Creation,
 * restart and public update advance the artifact revision. A renewal without a
 * public change does not.
 *
 * @param observationId observation committed or replayed
 * @param artifactName affected artifact
 * @param effectiveTime transaction-level confirmation time
 * @param created records with no active or due canonical predecessor
 * @param renewed still-active records confirmed in place
 * @param restarted due records closed and recreated with new identities
 * @param publicRowsUpdated active records whose public values changed
 * @param metadataOnlyRows renewed records whose ordering origin advanced without a public change
 * @param artifactRevision public-mutation revision observed after the transaction
 * @param requiredProjectionGeneration mutable-projection generation observed after the transaction
 * @param replayed whether an existing observation commit marker supplied this outcome
 */
public record LifecycleWriteResult(ObservationId observationId,
                                   String artifactName,
                                   EffectiveTime effectiveTime,
                                   int created,
                                   int renewed,
                                   int restarted,
                                   int publicRowsUpdated,
                                   int metadataOnlyRows,
                                   long artifactRevision,
                                   ProjectionGeneration requiredProjectionGeneration,
                                   boolean replayed) {

    /** Validates counts, identity and revision invariants. */
    public LifecycleWriteResult {
        Objects.requireNonNull(observationId, "observationId");
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        Objects.requireNonNull(requiredProjectionGeneration, "requiredProjectionGeneration");
        if (created < 0 || renewed < 0 || restarted < 0
                || publicRowsUpdated < 0 || metadataOnlyRows < 0) {
            throw new IllegalArgumentException("Lifecycle write counts must not be negative");
        }
        if (artifactRevision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (Math.addExact(newPublicRows(created, restarted), publicRowsUpdated) > 0
                && artifactRevision == 0) {
            throw new IllegalArgumentException("New public rows require a positive artifact revision");
        }
    }

    /** Returns the number of public rows inserted into active storage. */
    public int publicRowsInserted() {
        return newPublicRows(created, restarted);
    }

    /** Returns rows whose public projection changed through insert, restart or update. */
    public int publicRowsChanged() {
        return Math.addExact(publicRowsInserted(), publicRowsUpdated);
    }

    /** Returns the number of prepared records classified by the transaction. */
    public int confirmedRecords() {
        return Math.addExact(Math.addExact(publicRowsInserted(), renewed), publicRowsUpdated);
    }

    /** Compatibility constructor for pre-ordered-mutation callers. */
    public LifecycleWriteResult(ObservationId observationId,
                                String artifactName,
                                EffectiveTime effectiveTime,
                                int created,
                                int renewed,
                                int restarted,
                                long artifactRevision,
                                ProjectionGeneration requiredProjectionGeneration,
                                boolean replayed) {
        this(observationId, artifactName, effectiveTime, created, renewed, restarted,
                0, 0, artifactRevision, requiredProjectionGeneration, replayed);
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
