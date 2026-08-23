package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;

import java.util.Objects;

/** Pure field-level strategy evaluator for tri-state dataframe import merge. */
public final class ImportMergeResolver {

    /**
     * Resolves one incoming cell against the current public value.
     *
     * @param existingRecord whether matching found an existing active record
     * @param existingValue current nullable public value
     * @param incoming incoming tri-state cell
     * @param policy effective source/artifact/column policy
     * @return deterministic field decision
     */
    public ImportMergeResult resolve(boolean existingRecord,
                                     String existingValue,
                                     ImportCell incoming,
                                     ImportMergePolicy policy) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(policy, "policy");
        if (incoming.presence() == ImportCell.Presence.ABSENT) {
            return ImportMergeResult.unchanged();
        }
        if (!existingRecord) {
            return incoming.presence() == ImportCell.Presence.NULL
                    ? ImportMergeResult.unchanged()
                    : ImportMergeResult.set(incoming.value());
        }
        return switch (policy) {
            case KEEP_EXISTING -> ImportMergeResult.unchanged();
            case FILL_MISSING -> resolveFillMissing(existingValue, incoming);
            case REPLACE_NON_NULL -> resolveReplaceNonNull(existingValue, incoming);
            case AUTHORITATIVE -> resolveAuthoritative(existingValue, incoming);
            case REJECT_CONFLICT -> resolveRejectConflict(existingValue, incoming);
        };
    }

    private ImportMergeResult resolveFillMissing(String existingValue, ImportCell incoming) {
        if (existingValue == null && incoming.presence() == ImportCell.Presence.VALUE) {
            return ImportMergeResult.set(incoming.value());
        }
        return ImportMergeResult.unchanged();
    }

    private ImportMergeResult resolveReplaceNonNull(String existingValue, ImportCell incoming) {
        if (incoming.presence() != ImportCell.Presence.VALUE || Objects.equals(existingValue, incoming.value())) {
            return ImportMergeResult.unchanged();
        }
        return ImportMergeResult.set(incoming.value());
    }

    private ImportMergeResult resolveAuthoritative(String existingValue, ImportCell incoming) {
        if (incoming.presence() == ImportCell.Presence.NULL) {
            return existingValue == null ? ImportMergeResult.unchanged() : ImportMergeResult.clear();
        }
        return Objects.equals(existingValue, incoming.value())
                ? ImportMergeResult.unchanged()
                : ImportMergeResult.set(incoming.value());
    }

    private ImportMergeResult resolveRejectConflict(String existingValue, ImportCell incoming) {
        if (incoming.presence() == ImportCell.Presence.NULL) {
            return existingValue == null ? ImportMergeResult.unchanged() : ImportMergeResult.conflict();
        }
        if (existingValue == null) {
            return ImportMergeResult.set(incoming.value());
        }
        return Objects.equals(existingValue, incoming.value())
                ? ImportMergeResult.unchanged()
                : ImportMergeResult.conflict();
    }
}
