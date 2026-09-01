package com.iocextractor.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reports use of the bounded v0.2.0 artifact-identity compatibility shape. */
@Component
final class ArtifactIdentityCompatibilityReporter {

    private static final Logger log = LoggerFactory.getLogger(ArtifactIdentityCompatibilityReporter.class);

    private final IocProperties properties;

    ArtifactIdentityCompatibilityReporter(IocProperties properties) {
        this.properties = properties;
    }

    @EventListener
    public void onStarted(ApplicationStartedEvent event) {
        reportCompatibility();
    }

    void reportCompatibility() {
        properties.artifactIdentity().artifacts().stream()
                .filter(V020ArtifactIdentityCompatibility::appliesTo)
                .forEach(artifact -> log.warn(
                        "{}: artifact '{}' uses the exact v0.2.0 identity shape; migrate the operator configuration to explicit record-key, match-keys and epoch fields",
                        V020ArtifactIdentityCompatibility.DIAGNOSTIC_CODE,
                        artifact.name()));
    }
}
