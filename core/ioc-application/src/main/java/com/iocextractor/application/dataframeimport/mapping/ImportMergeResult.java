package com.iocextractor.application.dataframeimport.mapping;

import java.util.Objects;

/**
 * Storage-neutral result of resolving one imported field.
 *
 * @param decision required mutation/conflict decision
 * @param value final value for {@link Decision#SET}; otherwise null
 */
public record ImportMergeResult(Decision decision, String value) {

    /** Possible field-level outcomes before row atomicity is applied. */
    public enum Decision {
        /** Existing value remains unchanged. */
        UNCHANGED,
        /** Final value must be set to a non-null value. */
        SET,
        /** Final value must be cleared to null. */
        CLEAR,
        /** Incoming state conflicts with the configured merge contract. */
        CONFLICT
    }

    /** Enforces a value only for a set decision. */
    public ImportMergeResult {
        Objects.requireNonNull(decision, "decision");
        if ((decision == Decision.SET) != (value != null)) {
            throw new IllegalArgumentException("SET is the only merge decision that carries a value");
        }
    }

    /** @return unchanged result */
    public static ImportMergeResult unchanged() {
        return new ImportMergeResult(Decision.UNCHANGED, null);
    }

    /** @param value final non-null value @return set result */
    public static ImportMergeResult set(String value) {
        return new ImportMergeResult(Decision.SET, value);
    }

    /** @return explicit clear result */
    public static ImportMergeResult clear() {
        return new ImportMergeResult(Decision.CLEAR, null);
    }

    /** @return merge conflict result */
    public static ImportMergeResult conflict() {
        return new ImportMergeResult(Decision.CONFLICT, null);
    }
}
