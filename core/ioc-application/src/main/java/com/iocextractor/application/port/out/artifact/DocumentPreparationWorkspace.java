package com.iocextractor.application.port.out.artifact;

import java.nio.file.Path;
import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import java.util.List;

/** Private precommit state for one document; sealed rows are never canonical authority. */
public interface DocumentPreparationWorkspace extends AutoCloseable {
    /** Source snapshot owned by this invocation. */
    Path source();
    /** Marks private state disposable after success, dry-run or policy rejection. */
    void discard();
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
    @Override
    void close();
}
