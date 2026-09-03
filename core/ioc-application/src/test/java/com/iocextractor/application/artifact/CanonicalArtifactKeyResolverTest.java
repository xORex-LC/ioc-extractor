package com.iocextractor.application.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void identity_definition_rejects_ambiguous_versioned_formulas() {
        CanonicalKeyDefinition recordKey = new CanonicalKeyDefinition(
                "row-v1", CanonicalKeyMode.COMPOSITE, List.of("value"));
        CanonicalKeyDefinition matchKey = new CanonicalKeyDefinition(
                "match-v1", CanonicalKeyMode.FIRST_NON_EMPTY, List.of("value", "alias"));

        ArtifactIdentityDefinition definition = new ArtifactIdentityDefinition(
                "masks", recordKey, List.of(matchKey), 2);
        assertThat(definition.columns()).containsExactly("value");
        assertThat(definition.firstNonEmpty()).isFalse();
        assertThat(new ArtifactIdentityDefinition("hashes", List.of("md5"), true, 1)
                .firstNonEmpty()).isTrue();

        assertThatThrownBy(() -> new ArtifactIdentityDefinition(
                " ", recordKey, List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Artifact name");
        assertThatThrownBy(() -> new ArtifactIdentityDefinition(
                "masks", recordKey, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
        assertThatThrownBy(() -> new ArtifactIdentityDefinition(
                "masks", recordKey, List.of(matchKey, matchKey), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void key_definition_requires_a_named_unique_non_empty_formula() {
        assertThatThrownBy(() -> new CanonicalKeyDefinition(
                null, CanonicalKeyMode.COMPOSITE, List.of("value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definition id");
        assertThatThrownBy(() -> new CanonicalKeyDefinition(
                "row-v1", null, List.of("value")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("mode");
        assertThatThrownBy(() -> new CanonicalKeyDefinition(
                "row-v1", CanonicalKeyMode.COMPOSITE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        assertThatThrownBy(() -> new CanonicalKeyDefinition(
                "row-v1", CanonicalKeyMode.COMPOSITE, java.util.Arrays.asList("value", null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CanonicalKeyDefinition(
                "row-v1", CanonicalKeyMode.COMPOSITE, List.of("value", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        assertThatThrownBy(() -> new CanonicalKeyDefinition(
                "row-v1", CanonicalKeyMode.COMPOSITE, List.of("value", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void key_material_and_match_candidates_reject_unstable_identity_facts() {
        String hash = ArtifactIdentityDefinition.sha256("value");
        assertThat(new CanonicalKeyMaterial("row-v1", hash, "[\"value\"]"))
                .extracting(CanonicalKeyMaterial::keyHash)
                .isEqualTo(hash);

        assertThatThrownBy(() -> new CanonicalKeyMaterial(" ", hash, "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definition id");
        assertThatThrownBy(() -> new CanonicalKeyMaterial("row-v1", "A".repeat(64), "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case SHA-256");
        assertThatThrownBy(() -> new CanonicalKeyMaterial("row-v1", null, "[]"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("keyHash");
        assertThatThrownBy(() -> new CanonicalKeyMaterial("row-v1", hash, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("keyCanonical");

        assertThatThrownBy(() -> new CanonicalMatchCandidate(0, 1, new ArtifactRowKey("row")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new CanonicalMatchCandidate(1, 0, new ArtifactRowKey("row")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new CanonicalMatchCandidate(1, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rowKey");
    }

    @Test
    void match_request_requires_a_stable_correlation_identity() {
        String hash = ArtifactIdentityDefinition.sha256("value");
        CanonicalKeyMaterial key = new CanonicalKeyMaterial("row-v1", hash, "[\"value\"]");
        assertThat(new CanonicalMatchRequest("request-1", List.of(key)).keys())
                .containsExactly(key);

        assertThatThrownBy(() -> new CanonicalMatchRequest(null, List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id");
        assertThatThrownBy(() -> new CanonicalMatchRequest(" ", List.of(key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id");
    }

    @Test
    void match_plan_requires_consistent_cardinality_and_exact_candidate_access() {
        CanonicalMatchCandidate one = new CanonicalMatchCandidate(
                10, 100, new ArtifactRowKey("row-a"));
        CanonicalMatchCandidate two = new CanonicalMatchCandidate(
                11, 101, new ArtifactRowKey("row-b"));

        assertThat(CanonicalMatchPlan.from("one", List.of(one)).exactCandidate()).contains(one);
        assertThat(CanonicalMatchPlan.from("zero", List.of()).exactCandidate()).isEmpty();
        assertThat(CanonicalMatchPlan.from("many", List.of(one, two)).exactCandidate()).isEmpty();
        assertThatThrownBy(() -> new CanonicalMatchPlan(
                " ", CanonicalMatchCardinality.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id");
        assertThatThrownBy(() -> new CanonicalMatchPlan("request", null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("cardinality");
        assertThatThrownBy(() -> new CanonicalMatchPlan(
                "request", CanonicalMatchCardinality.ONE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardinality");
        assertThatThrownBy(() -> CanonicalMatchPlan.from(
                "request", java.util.Arrays.asList(one, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("candidates element");
    }

    @Test
    void mutation_and_id_reservation_contracts_reject_invalid_ranges() {
        for (CanonicalRecordMutationKind kind : List.of(
                CanonicalRecordMutationKind.INSERTED,
                CanonicalRecordMutationKind.RESTARTED,
                CanonicalRecordMutationKind.UPDATED,
                CanonicalRecordMutationKind.CLEARED)) {
            assertThat(new CanonicalRecordMutationOutcome(
                    kind, 1, 2, Set.of(), Set.of()).publicMutation()).isTrue();
        }
        assertThatThrownBy(() -> new CanonicalRecordMutationOutcome(
                null, 1, 2, Set.of(), Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("kind");
        assertThatThrownBy(() -> new CanonicalRecordMutationOutcome(
                CanonicalRecordMutationKind.NO_OP, 0, 2, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new CanonicalRecordMutationOutcome(
                CanonicalRecordMutationKind.NO_OP, 1, 0, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        ArtifactIdReservation ascending = new ArtifactIdReservation(10, 2, ArtifactIdStrategy.ASCENDING);
        ArtifactIdReservation descending = new ArtifactIdReservation(10, 2, ArtifactIdStrategy.DESCENDING);
        assertThat(ascending.idAt(1)).isEqualTo(11);
        assertThat(descending.idAt(1)).isEqualTo(9);
        assertThatThrownBy(() -> new ArtifactIdReservation(1, -1, ArtifactIdStrategy.ASCENDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> new ArtifactIdReservation(1, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("strategy");
        assertThatThrownBy(() -> ascending.idAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ascending.idAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void key_resolver_returns_empty_results_for_unmanaged_artifacts() {
        CanonicalArtifactKeyResolver resolver = new CanonicalArtifactKeyResolver(List.of());
        ArtifactRow row = new ArtifactRow(Map.of("value", "example.test"));

        assertThat(resolver.containsArtifact("unknown")).isFalse();
        assertThat(resolver.recordKeyOf("unknown", row)).isEmpty();
        assertThat(resolver.matchKeysOf("unknown", row)).isEmpty();
    }

    private static ArtifactRow row(String... pairs) {
        var values = new LinkedHashMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }
        return ArtifactRow.ordered(values);
    }
}
