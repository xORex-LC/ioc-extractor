package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import java.util.Map;

/** Creates an owned, quota-admitted private document preparation scope. */
@FunctionalInterface
public interface DocumentPreparationWorkspaceFactory {
    DocumentPreparationWorkspace open(com.iocextractor.application.port.in.ExtractionCommand command,
                                      Map<String, ArtifactWritePolicy> policies);
}
