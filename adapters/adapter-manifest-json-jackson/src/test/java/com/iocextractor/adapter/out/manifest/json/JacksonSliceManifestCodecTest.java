package com.iocextractor.adapter.out.manifest.json;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonSliceManifestCodecTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    private final JacksonSliceManifestCodec codec = new JacksonSliceManifestCodec();

    @Test
    void produces_stable_utf8_golden_bytes_with_unicode_escaping_and_explicit_null() {
        SliceManifest manifest = manifest(
                "репутация \"IOC\"",
                "маски-\"ioc\".csv",
                ArtifactCoverage.empty(),
                format());

        String json = new String(codec.encode(manifest), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("""
                {"manifest_version":1,"slice_id":"run-1","run_id":"run-1","profile":"репутация \\"IOC\\"","created_at":"2026-06-28T00:00:00Z","output_mode":"complete","plan_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","format":{"type":"csv","charset":"UTF-8","delimiter":";","quote":"\\\"","null_literal":"NULL"},"artifacts":[{"artifact":"masks","file":"маски-\\"ioc\\".csv","rows":0,"coverage":{"revision":0,"changed_at":null,"upper_id":0},"identity_epoch":1,"identity_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","schema_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]}
                """.strip());
        assertThat(codec.encode(manifest)).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void round_trips_complete_manifest_without_losing_order_or_coverage() {
        SliceManifest manifest = manifest(
                "reputation",
                "masks.csv",
                new ArtifactCoverage(7, NOW, 42),
                format());

        SliceManifest decoded = codec.decode(codec.encode(manifest));

        assertThat(decoded).isEqualTo(manifest);
        assertThat(decoded.artifacts()).extracting(SliceArtifactManifest::artifactName)
                .containsExactly("masks");
    }

    @Test
    void rejects_unknown_manifest_version_before_building_application_model() {
        byte[] bytes = new String(codec.encode(manifest(
                "reputation", "masks.csv", ArtifactCoverage.empty(), format())), StandardCharsets.UTF_8)
                .replace("\"manifest_version\":1", "\"manifest_version\":2")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.decode(bytes))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Unsupported slice manifest version: 2");
    }

    @Test
    void rejects_malformed_or_structurally_unknown_json() {
        assertThatThrownBy(() -> codec.decode("{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Failed to decode");

        byte[] unknownField = new String(codec.encode(manifest(
                "reputation", "masks.csv", ArtifactCoverage.empty(), format())), StandardCharsets.UTF_8)
                .replace("{\"manifest_version\":1", "{\"unexpected\":true,\"manifest_version\":1")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.decode(unknownField))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Failed to decode");
    }

    @Test
    void rejects_duplicate_properties_instead_of_accepting_an_ambiguous_manifest() {
        byte[] duplicateProfile = new String(codec.encode(manifest(
                "reputation", "masks.csv", ArtifactCoverage.empty(), format())), StandardCharsets.UTF_8)
                .replace("\"profile\":\"reputation\"",
                        "\"profile\":\"other\",\"profile\":\"reputation\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.decode(duplicateProfile))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Failed to decode");
    }

    @Test
    void runtime_map_insertion_order_cannot_change_wire_bytes() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("type", "csv");
        first.put("charset", "UTF-8");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("charset", "UTF-8");
        second.put("type", "csv");

        SliceManifest left = manifest(
                "reputation", "masks.csv", ArtifactCoverage.empty(), mappedFormat(first));
        SliceManifest right = manifest(
                "reputation", "masks.csv", ArtifactCoverage.empty(), mappedFormat(second));

        assertThat(codec.encode(left)).isEqualTo(codec.encode(right));
    }

    private SliceManifest manifest(String profile,
                                   String file,
                                   ArtifactCoverage coverage,
                                   ExportFormat format) {
        return new SliceManifest(
                1,
                "run-1",
                "run-1",
                profile,
                NOW,
                ExportMode.COMPLETE,
                HASH_A,
                format,
                List.of(new SliceArtifactManifest(
                        "masks", file, coverage.upperId(), coverage,
                        1, HASH_A, HASH_B, HASH_C)));
    }

    private ExportFormat format() {
        return new ExportFormat("csv", "UTF-8", ";", "\"", "NULL");
    }

    private ExportFormat mappedFormat(Map<String, String> values) {
        return new ExportFormat(values.get("type"), values.get("charset"), ";", "\"", "NULL");
    }
}
