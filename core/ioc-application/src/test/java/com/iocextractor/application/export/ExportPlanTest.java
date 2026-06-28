package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportPlanTest {

    @Test
    void plan_hash_is_deterministic_and_order_sensitive() {
        ExportPlan first = plan(List.of("masks", "hashes"));
        ExportPlan replay = plan(List.of("masks", "hashes"));
        ExportPlan reordered = plan(List.of("hashes", "masks"));

        assertThat(first.planHash()).isEqualTo(replay.planHash());
        assertThat(reordered.planHash()).isNotEqualTo(first.planHash());
    }

    @Test
    void plan_requires_specs_to_match_profile_order() {
        ExportProfile profile = new ExportProfile("default", ExportMode.COMPLETE, List.of("masks", "hashes"));

        assertThatThrownBy(() -> new ExportPlan(1, profile, format(), List.of(spec("hashes"), spec("masks"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile order");
    }

    @Test
    void profiles_and_specs_defensively_copy_ordered_collections() {
        var names = new ArrayList<>(List.of("masks"));
        var columns = new ArrayList<>(List.of("id", "mask"));
        ExportProfile profile = new ExportProfile("default", ExportMode.COMPLETE, names);
        ExportArtifactSpec spec = new ExportArtifactSpec(
                "masks", "masks.csv", columns, 1, hash('a'), hash('b'), hash('c'));
        names.clear();
        columns.clear();

        assertThat(profile.artifacts()).containsExactly("masks");
        assertThat(spec.columns()).containsExactly("id", "mask");
        assertThatThrownBy(() -> profile.artifacts().add("hashes"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void artifact_file_name_must_be_a_leaf() {
        assertThatThrownBy(() -> new ExportArtifactSpec(
                "masks", "../masks.csv", List.of("id"), 1, hash('a'), hash('b'), hash('c')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single relative path segment");
    }

    private ExportPlan plan(List<String> names) {
        ExportProfile profile = new ExportProfile("default", ExportMode.COMPLETE, names);
        return new ExportPlan(1, profile, format(), names.stream().map(this::spec).toList());
    }

    private ExportArtifactSpec spec(String name) {
        return new ExportArtifactSpec(name, name + ".csv", List.of("id", "value"),
                1, hash('a'), hash('b'), hash('c'));
    }

    private ExportFormat format() {
        return new ExportFormat("csv", "UTF-8", ";", "\"", "NULL");
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
