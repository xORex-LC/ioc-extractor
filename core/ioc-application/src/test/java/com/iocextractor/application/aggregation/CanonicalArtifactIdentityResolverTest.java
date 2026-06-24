package com.iocextractor.application.aggregation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalArtifactIdentityResolverTest {

    @Test
    void row_key_is_sha256_of_canonical_json_array() {
        var resolver = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));

        assertThat(resolver.keyOf("masks", row("mask", " example.com ")))
                .get()
                .extracting(ArtifactRowKey::value)
                .isEqualTo(ArtifactIdentityDefinition.sha256("[\"example.com\"]"));
    }

    @Test
    void composite_identity_keeps_explicit_nulls() {
        var resolver = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("addresses", List.of("forbidden_url", "forbidden_ip"), false, 1)));

        assertThat(resolver.keyOf("addresses", row("forbidden_url", "NULL", "forbidden_ip", "10.0.0.1")))
                .get()
                .extracting(ArtifactRowKey::value)
                .isEqualTo(ArtifactIdentityDefinition.sha256("[null,\"10.0.0.1\"]"));
    }

    @Test
    void first_non_empty_identity_includes_selected_column() {
        var resolver = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("hashes", List.of("hash_md5", "hash_sha1", "hash_sha256"), true, 1)));

        assertThat(resolver.keyOf("hashes", row(
                        "hash_md5", "NULL",
                        "hash_sha1", "ABC",
                        "hash_sha256", "NULL")))
                .get()
                .extracting(ArtifactRowKey::value)
                .isEqualTo(ArtifactIdentityDefinition.sha256("[\"hash_sha1\",\"ABC\"]"));
    }

    @Test
    void all_null_identity_is_unresolvable() {
        var resolver = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));

        assertThat(resolver.keyOf("masks", row("mask", "NULL"))).isEmpty();
    }

    @Test
    void identity_hash_changes_when_epoch_independent_formula_changes() {
        var single = new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1);
        var composite = new ArtifactIdentityDefinition("masks", List.of("mask", "source"), false, 1);

        assertThat(single.identityHash()).isNotEqualTo(composite.identityHash());
    }

    private ArtifactRow row(String... pairs) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return new ArtifactRow(Map.copyOf(values));
    }
}
