package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.port.in.ExtractionCommand;
import java.util.Map;

/** Creates an owned, quota-admitted private document preparation scope. */
@FunctionalInterface
public interface DocumentPreparationWorkspaceFactory {
    /** Maximum simultaneously owned preparations; ordered dispatch must not exceed this capacity. */
    default int maximumConcurrentWorkspaces() { return Integer.MAX_VALUE; }

    DocumentPreparationWorkspace open(ExtractionCommand command,
                                      Map<String, ArtifactWritePolicy> policies);

    /** Reopens an existing pinned workspace without copying the inbox source or creating a new plan. */
    default DocumentPreparationWorkspace reopen(ExtractionCommand command, Map<String, ArtifactWritePolicy> policies) {
        throw new UnsupportedOperationException("Document workspace recovery is unavailable");
    }

    /** Removes a completed plan by its exact durable reference, idempotently. */
    default void discardPlan(ObservationId observation, DocumentPreparationSeal seal) {
        throw new UnsupportedOperationException("Document workspace recovery is unavailable");
    }

    /** Removes a local pin after the service journal durably rejected its unlinked run. */
    default void discardUnlinkedPlan(ObservationId observation, String runId) {
        throw new UnsupportedOperationException("Document workspace recovery is unavailable");
    }
}
