package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.StoredArtifactIdentity;

import java.util.List;

/**
 * Driven port for per-artifact identity formula guardrails.
 */
public interface ArtifactIdentityStore {

    StoredArtifactIdentity ensure(ArtifactIdentityDefinition definition);

    default List<StoredArtifactIdentity> ensureAll(List<ArtifactIdentityDefinition> definitions) {
        return definitions.stream()
                .map(this::ensure)
                .toList();
    }
}
