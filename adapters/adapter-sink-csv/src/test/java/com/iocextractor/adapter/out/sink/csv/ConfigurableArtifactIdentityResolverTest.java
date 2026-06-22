package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.ArtifactRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableArtifactIdentityResolverTest {

    @Test
    void resolves_mask_key_from_single_column() {
        var resolver = new ConfigurableArtifactIdentityResolver(List.of(
                new ArtifactKeyDefinition("masks", List.of("mask"), false)));

        assertThat(resolver.keyOf("masks", new ArtifactRow(Map.of("mask", "example.com"))))
                .get()
                .extracting("value")
                .isEqualTo("mask:example.com");
    }

    @Test
    void resolves_hash_key_from_first_non_empty_column() {
        var resolver = new ConfigurableArtifactIdentityResolver(List.of(
                new ArtifactKeyDefinition("hashes", List.of("hash_md5", "hash_sha1", "hash_sha256"), true)));

        assertThat(resolver.keyOf("hashes", new ArtifactRow(Map.of(
                        "hash_md5", "NULL",
                        "hash_sha1", "ABC",
                        "hash_sha256", "NULL"))))
                .get()
                .extracting("value")
                .isEqualTo("hash_sha1:ABC");
    }
}
