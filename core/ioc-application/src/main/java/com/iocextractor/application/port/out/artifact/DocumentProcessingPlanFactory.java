package com.iocextractor.application.port.out.artifact;

import java.util.List;

/** Creates a document plan bound to one run's artifact preparers. */
@FunctionalInterface
public interface DocumentProcessingPlanFactory {
    DocumentProcessingPlan create(List<ArtifactPreparer> preparers);
}
