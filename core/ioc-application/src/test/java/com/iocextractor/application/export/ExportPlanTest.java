package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportPlanTest {

    @Test
    void csv_layout_changes_invalidate_the_plan_without_changing_schema_or_mapping() {
        ExportPlan original = plan(List.of("masks"));
        var layouts = List.of(
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL",
                        ExportRecordSeparator.LF, ExportQuoting.ALL, ExportQuoting.ALL),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL",
                        ExportRecordSeparator.CRLF, ExportQuoting.NONE, ExportQuoting.ALL),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL",
                        ExportRecordSeparator.CRLF, ExportQuoting.ALL, ExportQuoting.MINIMAL));

        assertThat(layouts).allSatisfy(layout -> {
            var changed = new ExportPlan(layout.requiredManifestVersion(), original.profile(), layout,
                    original.artifacts());
            assertThat(changed.planHash()).isNotEqualTo(original.planHash());
            assertThat(changed.artifacts()).isEqualTo(original.artifacts());
            assertThatThrownBy(() -> new ExportPlan(1, original.profile(), layout, original.artifacts()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest version 2");
        });
        assertThat(layouts.stream().map(layout -> new ExportPlan(layout.requiredManifestVersion(),
                original.profile(), layout, original.artifacts()).planHash()).toList())
                .doesNotHaveDuplicates();
        assertThat(format().recordSeparator().characters()).isEqualTo("\r\n");
    }

    @Test
    void plan_hash_is_deterministic_and_order_sensitive() {
        ExportPlan first = plan(List.of("masks", "hashes"));
        ExportPlan replay = plan(List.of("masks", "hashes"));
        ExportPlan reordered = plan(List.of("hashes", "masks"));

        // Fixed fingerprint from the original CRLF/ALL/ALL contract, before layout options.
        assertThat(first.planHash()).isEqualTo("a014ed66681f7cb3caaed658d49977ca8a7f5c5b6912faf4beb77630899643c1");
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
