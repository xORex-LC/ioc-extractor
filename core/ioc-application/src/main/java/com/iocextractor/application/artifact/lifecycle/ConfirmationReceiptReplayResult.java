package com.iocextractor.application.artifact.lifecycle;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/** Durable lifecycle outcomes produced without re-running ETL. */
public record ConfirmationReceiptReplayResult(Map<String, LifecycleWriteResult> artifacts) {

    public ConfirmationReceiptReplayResult {
        artifacts = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(artifacts, "artifacts")));
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("Receipt replay must contain at least one artifact");
        }
    }

    /** New public membership by artifact, suitable for the existing export nudge contract. */
    public Map<String, Integer> insertedPerArtifact() {
        var inserted = new LinkedHashMap<String, Integer>();
        artifacts.forEach((artifact, result) -> inserted.put(artifact, result.publicRowsInserted()));
        return java.util.Collections.unmodifiableMap(inserted);
    }

    /** Artifacts whose public projection changed during the original commit. */
    public java.util.Set<String> changedArtifacts() {
        var changed = new LinkedHashSet<String>();
        artifacts.forEach((artifact, result) -> {
            if (result.publicRowsChanged() > 0) {
                changed.add(artifact);
            }
        });
        return java.util.Collections.unmodifiableSet(changed);
    }
}
