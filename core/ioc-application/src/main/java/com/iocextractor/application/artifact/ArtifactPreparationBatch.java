package com.iocextractor.application.artifact;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicatorOccurrence;

import java.util.List;
import java.util.Objects;

/** Legacy retained IOCs plus occurrence-preserving candidates for opt-in artifact policies. */
public record ArtifactPreparationBatch(List<ClassifiedIndicator> retained,
                                       List<ClassifiedIndicatorOccurrence> occurrences) {

    public ArtifactPreparationBatch {
        retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
    }
}
