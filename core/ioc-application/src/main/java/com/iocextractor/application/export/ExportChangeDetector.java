package com.iocextractor.application.export;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure policy for cheap revision gating and authoritative post-materialization hash comparison.
 *
 * <p>The pre-gate may avoid all export IO. Once a snapshot has been materialized, only manifest
 * coverage and hashes are authoritative; current mutable canonical state is never reread.
 */
public final class ExportChangeDetector {

    /** Returns whether revisions, plan identity or progress coverage require a candidate run. */
    public boolean requiresMaterialization(ExportPlan plan,
                                           List<ArtifactRevision> revisions,
                                           List<ExportProgress> progress) {
        Objects.requireNonNull(plan, "plan");
        List<ArtifactRevision> current = List.copyOf(Objects.requireNonNull(revisions, "revisions"));
        List<ExportProgress> previous = List.copyOf(Objects.requireNonNull(progress, "progress"));
        if (current.size() != plan.artifacts().size() || previous.size() != plan.artifacts().size()) {
            return true;
        }
        Map<String, ExportProgress> byArtifact = index(previous);
        for (int index = 0; index < plan.artifacts().size(); index++) {
            String artifact = plan.artifacts().get(index).artifactName();
            ArtifactRevision revision = current.get(index);
            ExportProgress saved = byArtifact.get(artifact);
            if (!artifact.equals(revision.artifactName()) || saved == null
                    || saved.lastRevision() != revision.revision()
                    || !saved.planHash().equals(plan.planHash())) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether candidate data bytes equal the previous completed slice for this plan. */
    public boolean sameContent(SliceManifest candidate, List<ExportProgress> progress) {
        Objects.requireNonNull(candidate, "candidate");
        List<ExportProgress> previous = List.copyOf(Objects.requireNonNull(progress, "progress"));
        if (candidate.artifacts().size() != previous.size()) {
            return false;
        }
        Map<String, ExportProgress> byArtifact = index(previous);
        return candidate.artifacts().stream().allMatch(artifact -> {
            ExportProgress saved = byArtifact.get(artifact.artifactName());
            return saved != null
                    && saved.planHash().equals(candidate.planHash())
                    && saved.lastSha256().equals(artifact.sha256());
        });
    }

    /** Builds terminal progress for a newly available immutable slice. */
    public List<ExportProgress> completedProgress(SliceManifest manifest, Instant updatedAt) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return manifest.artifacts().stream()
                .map(artifact -> new ExportProgress(
                        manifest.profile(), artifact.artifactName(), artifact.coverage().revision(),
                        artifact.sha256(), manifest.sliceId(), manifest.planHash(), updatedAt))
                .toList();
    }

    /**
     * Advances snapshot revisions for an identical candidate while retaining hashes and slice id
     * of the previously published slice.
     */
    public List<ExportProgress> skippedProgress(SliceManifest candidate,
                                                List<ExportProgress> progress,
                                                Instant updatedAt) {
        if (!sameContent(candidate, progress)) {
            throw new IllegalArgumentException("Skipped progress requires byte-identical candidate content");
        }
        Map<String, ExportProgress> previous = index(progress);
        return candidate.artifacts().stream()
                .map(artifact -> {
                    ExportProgress saved = previous.get(artifact.artifactName());
                    return new ExportProgress(
                            candidate.profile(), artifact.artifactName(), artifact.coverage().revision(),
                            saved.lastSha256(), saved.lastSliceId(), candidate.planHash(), updatedAt);
                })
                .toList();
    }

    private Map<String, ExportProgress> index(List<ExportProgress> progress) {
        Map<String, ExportProgress> result = new LinkedHashMap<>();
        for (ExportProgress item : progress) {
            if (result.put(item.artifactName(), item) != null) {
                throw new IllegalArgumentException("Duplicate export progress artifact: " + item.artifactName());
            }
        }
        return result;
    }
}
