package com.iocextractor.application.port.out.artifact;

import java.nio.file.Path;
import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import java.util.List;

/** Private precommit state for one document; sealed rows are never canonical authority. */
public interface DocumentPreparationWorkspace extends AutoCloseable {
    /** Source snapshot owned by this invocation. */
    Path source();
    /** Opens invocation-local source scratch, removed before sealing or on close. */
    default DocumentSourceWorkspace sourceWorkspace() {
        throw new UnsupportedOperationException("Document source workspace is required");
    }
    /** Marks private state disposable after success, dry-run or policy rejection. */
    void discard();
    /** Reports whether a checkpoint-approved plan is retained for a durable run. */
    default boolean recoveryPinned() { return false; }
    /** Durably enters promotion after the rejecting checkpoint and before any reservation. */
    void beginPromotion();
    /** Includes a previous attempt that already entered promotion. */
    boolean promotionStarted();
    /** Returns true only for the first occurrence of an original dedup identity. */
    boolean firstOriginal(String key);
    /** Stores a fully routed candidate and its selection eligibility. */
    void append(RoutedArtifactCandidate candidate, boolean eligible, boolean retainObservations);
    /** Completes global selection and validates the immutable seal. */
    List<ArtifactWritePlan> seal(List<ArtifactWritePlan> descriptors,
            DocumentPreparationSummary summary);

    /** Pins an already sealed, checkpoint-approved plan for the named run. */
    default DocumentPreparationSeal pinForRecovery(String runId, List<ArtifactWritePlan> plans,
                                                   DocumentPreparationSummary summary) {
        throw new UnsupportedOperationException("Document workspace recovery is unavailable");
    }

    /** Reads a local checkpoint reference before a run has linked it. */
    default DocumentPreparationSeal recoverySeal(List<ArtifactWritePlan> descriptors) {
        throw new UnsupportedOperationException("Document workspace recovery is unavailable");
    }

    /** Validates the exact pin and opens the immutable prepared rows without reprocessing input. */
    default RestoredPreparationResult restore(String runId, DocumentPreparationSeal seal,
                                       List<ArtifactWritePlan> descriptors) {
        throw new UnsupportedOperationException("Document workspace recovery is unavailable");
    }

    /** Verified row sources retain the workspace lease and may be read only before close. */
    record RestoredPreparationResult(List<ArtifactWritePlan> plans, DocumentPreparationSummary summary) {
        public RestoredPreparationResult {
            plans = List.copyOf(plans);
            java.util.Objects.requireNonNull(summary, "summary");
        }
    }

    @Override
    void close();
}
