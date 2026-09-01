package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.CanonicalKeyMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ArtifactIdentityConfigurationResolverTest {

    @Test
    void resolvesExactV020ShapesToCurrentVersionedDefinitions() {
        var masks = ArtifactIdentityConfigurationResolver.resolve(
                legacy("masks", null, "mask"));
        var ipList = ArtifactIdentityConfigurationResolver.resolve(
                legacy("ip_list", null, "ip"));
        var addresses = ArtifactIdentityConfigurationResolver.resolve(
                legacy("address_blacklist", ArtifactKeyMode.FIRST_NON_EMPTY,
                        "forbidden_url", "forbidden_ip"));
        var hashes = ArtifactIdentityConfigurationResolver.resolve(
                legacy("hashes", ArtifactKeyMode.FIRST_NON_EMPTY,
                        "hash_md5", "hash_sha1", "hash_sha256"));

        assertThat(masks.epoch()).isEqualTo(1);
        assertThat(masks.recordKey().definitionId()).isEqualTo("mask-row-v1");
        assertThat(masks.matchKeys())
                .extracting(key -> key.definitionId(), key -> key.columns())
                .containsExactly(tuple("mask-v1", List.of("mask")));
        assertThat(ipList.epoch()).isEqualTo(1);
        assertThat(ipList.recordKey().definitionId()).isEqualTo("ip-row-v1");
        assertThat(ipList.matchKeys()).extracting(key -> key.definitionId())
                .containsExactly("ip-v1");
        assertThat(addresses.epoch()).isEqualTo(2);
        assertThat(addresses.recordKey().definitionId()).isEqualTo("address-row-v2");
        assertThat(addresses.recordKey().mode()).isEqualTo(CanonicalKeyMode.COMPOSITE);
        assertThat(addresses.matchKeys())
                .extracting(key -> key.definitionId(), key -> key.columns())
                .containsExactly(
                        tuple("forbidden-url-v1", List.of("forbidden_url")),
                        tuple("forbidden-ip-v1", List.of("forbidden_ip")));
        assertThat(hashes.epoch()).isEqualTo(2);
        assertThat(hashes.recordKey().definitionId()).isEqualTo("hash-row-v2");
        assertThat(hashes.matchKeys()).extracting(key -> key.definitionId())
                .containsExactly("hash-md5-v1", "hash-sha1-v1", "hash-sha256-v1");
    }

    @Test
    void preservesExplicitCurrentDefinition() {
        var configured = new IocProperties.ArtifactIdentity.Artifact(
                "custom", List.of("value"), ArtifactKeyMode.FIRST_NON_EMPTY, 3,
                "custom-row-v3",
                List.of(new IocProperties.ArtifactIdentity.Artifact.MatchKey(
                        "custom-value-v1", List.of("value"))));

        var resolved = ArtifactIdentityConfigurationResolver.resolve(configured);

        assertThat(resolved.epoch()).isEqualTo(3);
        assertThat(resolved.recordKey().definitionId()).isEqualTo("custom-row-v3");
        assertThat(resolved.recordKey().mode()).isEqualTo(CanonicalKeyMode.FIRST_NON_EMPTY);
        assertThat(resolved.matchKeys()).extracting(key -> key.definitionId())
                .containsExactly("custom-value-v1");
        assertThat(V020ArtifactIdentityCompatibility.appliesTo(configured)).isFalse();
    }

    @Test
    void rejectsSimilarButUnapprovedIncompleteShape() {
        var unknown = legacy("custom", null, "value");
        var changedBuiltIn = legacy("masks", null, "mask", "source");
        var explicitBlank = new IocProperties.ArtifactIdentity.Artifact(
                "masks", List.of("mask"), null, null, " ", null);

        assertThatThrownBy(() -> ArtifactIdentityConfigurationResolver.resolve(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record-key");
        assertThatThrownBy(() -> ArtifactIdentityConfigurationResolver.resolve(changedBuiltIn))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record-key");
        assertThatThrownBy(() -> ArtifactIdentityConfigurationResolver.resolve(explicitBlank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record-key");
        assertThat(V020ArtifactIdentityCompatibility.appliesTo(unknown)).isFalse();
        assertThat(V020ArtifactIdentityCompatibility.appliesTo(changedBuiltIn)).isFalse();
        assertThat(V020ArtifactIdentityCompatibility.appliesTo(explicitBlank)).isFalse();
    }

    private IocProperties.ArtifactIdentity.Artifact legacy(
            String name,
            ArtifactKeyMode keyMode,
            String... columns) {
        return new IocProperties.ArtifactIdentity.Artifact(
                name, List.of(columns), keyMode, null, null, null);
    }
}
