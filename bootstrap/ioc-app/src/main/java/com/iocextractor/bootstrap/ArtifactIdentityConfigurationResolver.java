package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;

import java.util.List;
import java.util.Objects;

/** Resolves typed current artifact-identity configuration into the application contract. */
final class ArtifactIdentityConfigurationResolver {

    private ArtifactIdentityConfigurationResolver() {
    }

    static ArtifactIdentityDefinition resolve(IocProperties.ArtifactIdentity.Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return V020ArtifactIdentityCompatibility.resolve(artifact)
                .orElseGet(() -> resolveCurrent(artifact));
    }

    private static ArtifactIdentityDefinition resolveCurrent(
            IocProperties.ArtifactIdentity.Artifact artifact) {
        if (!hasText(artifact.recordKey())) {
            throw new IllegalArgumentException(
                    "Artifact identity record-key must not be blank: " + artifact.name());
        }
        int epoch = artifact.epoch() == null ? 1 : artifact.epoch();
        CanonicalKeyDefinition recordKey = new CanonicalKeyDefinition(
                artifact.recordKey(), modeOf(artifact.keyMode()), artifact.keyColumns());
        List<IocProperties.ArtifactIdentity.Artifact.MatchKey> configuredMatchKeys =
                artifact.matchKeys() == null ? List.of() : artifact.matchKeys();
        List<CanonicalKeyDefinition> matchKeys = configuredMatchKeys.stream()
                .map(match -> new CanonicalKeyDefinition(
                        match.name(), CanonicalKeyMode.COMPOSITE, match.keyColumns()))
                .toList();
        return new ArtifactIdentityDefinition(artifact.name(), recordKey, matchKeys, epoch);
    }

    private static CanonicalKeyMode modeOf(ArtifactKeyMode mode) {
        return mode == ArtifactKeyMode.FIRST_NON_EMPTY
                ? CanonicalKeyMode.FIRST_NON_EMPTY
                : CanonicalKeyMode.COMPOSITE;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
