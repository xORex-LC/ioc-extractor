package com.iocextractor.application.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalArtifactKeyResolverTest {

    @Test
    void resolves_compound_record_key_and_only_usable_match_aliases() {
        var definitions = new ArtifactIdentityDefinition(
                "address_blacklist",
                new CanonicalKeyDefinition("address-row-v2", CanonicalKeyMode.COMPOSITE,
                        List.of("forbidden_url", "forbidden_ip")),
                List.of(
                        new CanonicalKeyDefinition("forbidden-url-v1", CanonicalKeyMode.COMPOSITE,
                                List.of("forbidden_url")),
                        new CanonicalKeyDefinition("forbidden-ip-v1", CanonicalKeyMode.COMPOSITE,
                                List.of("forbidden_ip"))),
                2);
        var resolver = new CanonicalArtifactKeyResolver(List.of(definitions));
        ArtifactRow row = row("forbidden_url", "https://bad.test", "forbidden_ip", null);

        assertThat(resolver.recordKeyOf("address_blacklist", row)).get().satisfies(key -> {
            assertThat(key.definitionId()).isEqualTo("address-row-v2");
            assertThat(key.keyCanonical()).isEqualTo("[\"https://bad.test\",null]");
        });
        assertThat(resolver.matchKeysOf("address_blacklist", row))
                .extracting(CanonicalKeyMaterial::definitionId)
                .containsExactly("forbidden-url-v1");
    }

    @Test
    void match_plan_deduplicates_alias_hits_before_cardinality() {
        var first = new CanonicalMatchCandidate(10, 100, new ArtifactRowKey("row-a"));
        var second = new CanonicalMatchCandidate(11, 101, new ArtifactRowKey("row-b"));

        assertThat(CanonicalMatchPlan.from("one", List.of(first, first)).cardinality())
                .isEqualTo(CanonicalMatchCardinality.ONE);
        assertThat(CanonicalMatchPlan.from("many", List.of(first, second)).cardinality())
                .isEqualTo(CanonicalMatchCardinality.MULTIPLE);
        assertThat(CanonicalMatchPlan.from("zero", List.of()).cardinality())
                .isEqualTo(CanonicalMatchCardinality.ZERO);
    }

    @Test
    void mutation_outcome_distinguishes_public_changes_from_confirmation_and_no_op() {
        assertThat(new CanonicalRecordMutationOutcome(
                CanonicalRecordMutationKind.CLEARED, 1, 2, Set.of("score"), Set.of("description"))
                .publicMutation()).isTrue();
        assertThat(new CanonicalRecordMutationOutcome(
                CanonicalRecordMutationKind.TTL_CONFIRMED, 1, 2, Set.of(), Set.of())
                .publicMutation()).isFalse();
        assertThat(new CanonicalRecordMutationOutcome(
                CanonicalRecordMutationKind.NO_OP, 1, 2, Set.of(), Set.of())
                .publicMutation()).isFalse();
        assertThatThrownBy(() -> new CanonicalRecordMutationOutcome(
                CanonicalRecordMutationKind.UPDATED, 1, 2, Set.of("source"), Set.of("source")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArtifactRow row(String... pairs) {
        var values = new LinkedHashMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }
        return ArtifactRow.ordered(values);
    }
}
