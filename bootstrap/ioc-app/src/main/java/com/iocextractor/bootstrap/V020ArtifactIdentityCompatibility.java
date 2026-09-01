package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded adapter for the four exact artifact identities shipped by v0.2.0.
 *
 * <p>Remove this class and its reporter after direct v0.2.0 upgrade and rollback
 * cease to be supported and operators are required to use explicit current
 * identity definitions. Similar or partial definitions are deliberately not
 * inferred.</p>
 */
final class V020ArtifactIdentityCompatibility {

    static final String DIAGNOSTIC_CODE = "CONFIG.LEGACY_ARTIFACT_IDENTITY";

    private static final Map<LegacyShape, CurrentShape> SHAPES = legacyShapes();

    private V020ArtifactIdentityCompatibility() {
    }

    static Optional<ArtifactIdentityDefinition> resolve(
            IocProperties.ArtifactIdentity.Artifact artifact) {
        if (!matchesCompatibilityEnvelope(artifact)) {
            return Optional.empty();
        }
        CurrentShape shape = SHAPES.get(new LegacyShape(
                artifact.name(), artifact.keyColumns(), artifact.keyMode()));
        return Optional.ofNullable(shape).map(current -> current.toDefinition(artifact.name()));
    }

    static boolean appliesTo(IocProperties.ArtifactIdentity.Artifact artifact) {
        return resolve(artifact).isPresent();
    }

    private static boolean matchesCompatibilityEnvelope(
            IocProperties.ArtifactIdentity.Artifact artifact) {
        return artifact != null
                && artifact.recordKey() == null
                && artifact.epoch() == null
                && (artifact.matchKeys() == null || artifact.matchKeys().isEmpty());
    }

    private static Map<LegacyShape, CurrentShape> legacyShapes() {
        Map<LegacyShape, CurrentShape> result = new LinkedHashMap<>();
        result.put(
                new LegacyShape("masks", List.of("mask"), null),
                new CurrentShape(
                        1, "mask-row-v1", List.of("mask"),
                        List.of(new MatchShape("mask-v1", List.of("mask")))));
        result.put(
                new LegacyShape("ip_list", List.of("ip"), null),
                new CurrentShape(
                        1, "ip-row-v1", List.of("ip"),
                        List.of(new MatchShape("ip-v1", List.of("ip")))));
        List<MatchShape> addressMatchKeys = List.of(
                new MatchShape("forbidden-url-v1", List.of("forbidden_url")),
                new MatchShape("forbidden-ip-v1", List.of("forbidden_ip")));
        result.put(
                new LegacyShape(
                        "address_blacklist",
                        List.of("forbidden_url", "forbidden_ip"),
                        ArtifactKeyMode.FIRST_NON_EMPTY),
                new CurrentShape(
                        2, "address-row-v2", List.of("forbidden_url", "forbidden_ip"),
                        addressMatchKeys));
        List<MatchShape> hashMatchKeys = List.of(
                new MatchShape("hash-md5-v1", List.of("hash_md5")),
                new MatchShape("hash-sha1-v1", List.of("hash_sha1")),
                new MatchShape("hash-sha256-v1", List.of("hash_sha256")));
        result.put(
                new LegacyShape(
                        "hashes",
                        List.of("hash_md5", "hash_sha1", "hash_sha256"),
                        ArtifactKeyMode.FIRST_NON_EMPTY),
                new CurrentShape(
                        2, "hash-row-v2", List.of("hash_md5", "hash_sha1", "hash_sha256"),
                        hashMatchKeys));
        return Map.copyOf(result);
    }

    private record LegacyShape(String name, List<String> keyColumns, ArtifactKeyMode keyMode) {

        private LegacyShape {
            keyColumns = keyColumns == null ? null : List.copyOf(keyColumns);
        }
    }

    private record CurrentShape(int epoch,
                                String recordKey,
                                List<String> recordColumns,
                                List<MatchShape> matchKeys) {

        private ArtifactIdentityDefinition toDefinition(String artifactName) {
            return new ArtifactIdentityDefinition(
                    artifactName,
                    new CanonicalKeyDefinition(recordKey, CanonicalKeyMode.COMPOSITE, recordColumns),
                    matchKeys.stream()
                            .map(match -> new CanonicalKeyDefinition(
                                    match.name(), CanonicalKeyMode.COMPOSITE, match.columns()))
                            .toList(),
                    epoch);
        }
    }

    private record MatchShape(String name, List<String> columns) {
    }
}
