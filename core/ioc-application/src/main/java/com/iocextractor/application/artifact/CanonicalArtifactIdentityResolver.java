package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;

import java.util.List;
import java.util.Optional;

/**
 * Canonical row-key resolver for dataframe storage.
 *
 * <p>The row key is {@code SHA-256} over a canonical JSON array. Nulls are
 * encoded explicitly, and first-non-empty identities include the selected column
 * name to avoid cross-column hash collisions.
 */
public final class CanonicalArtifactIdentityResolver implements ArtifactIdentityResolver {

    private final CanonicalArtifactKeyResolver resolver;

    public CanonicalArtifactIdentityResolver(List<ArtifactIdentityDefinition> definitions) {
        this.resolver = new CanonicalArtifactKeyResolver(definitions);
    }

    @Override
    public Optional<ArtifactRowKey> keyOf(String artifactName, ArtifactRow row) {
        return resolver.recordKeyOf(artifactName, row)
                .map(CanonicalKeyMaterial::keyHash)
                .map(ArtifactRowKey::new);
    }

    static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u%04x".formatted((int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
