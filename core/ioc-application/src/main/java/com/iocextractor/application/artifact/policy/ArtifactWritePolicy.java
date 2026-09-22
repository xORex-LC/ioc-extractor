package com.iocextractor.application.artifact.policy;

import java.util.Map;
import java.util.Objects;

/** Immutable per-artifact policy; absent configuration compiles to legacy keep-first behavior. */
public record ArtifactWritePolicy(DuplicateSelection duplicateSelection,
                                  String selectionColumn,
                                  Map<String, FieldUpdatePolicy> fields) {

    public ArtifactWritePolicy {
        Objects.requireNonNull(duplicateSelection, "duplicateSelection");
        fields = Map.copyOf(fields);
        if (duplicateSelection == DuplicateSelection.LAST_NONEMPTY
                && (selectionColumn == null || selectionColumn.isBlank())) {
            throw new IllegalArgumentException("Last-nonempty selection requires a column");
        }
    }

    public static ArtifactWritePolicy legacy() {
        return new ArtifactWritePolicy(DuplicateSelection.KEEP_FIRST, null, Map.of());
    }

    public enum DuplicateSelection {
        KEEP_FIRST,
        LAST_NONEMPTY
    }

    public enum FieldUpdatePolicy {
        LATEST_REGISTERED_KEEP_EXISTING
    }
}
