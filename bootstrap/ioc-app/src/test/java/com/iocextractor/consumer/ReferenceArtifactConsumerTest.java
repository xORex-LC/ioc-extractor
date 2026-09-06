package com.iocextractor.consumer;

import com.iocextractor.application.tck.junit.ContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ContractTest
class ReferenceArtifactConsumerTest {

    private static final List<String> MASKS_HEADER = List.of(
            "id", "mask", "url_match", "host_match", "score",
            "time_last_seen", "time_first_seen", "threat_type", "source", "description");

    private final ReferenceArtifactConsumer consumer = new ReferenceArtifactConsumer();

    @TempDir
    Path tempDir;

    @Test
    void consumes_exact_golden_complete_slice_without_producer_types() throws Exception {
        Path slice = fixture();

        var document = consumer.readSlice(slice, Map.of("masks", MASKS_HEADER));

        assertThat(document.sliceId()).isEqualTo("consumer-fixture-1");
        assertThat(document.profile()).isEqualTo("reputation-lists");
        assertThat(document.artifacts()).containsOnlyKeys("masks");
        assertThat(document.artifacts().get("masks").rows())
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("id", "1")
                        .containsEntry("mask", "example.org")
                        .containsEntry("host_match", null));
    }

    @Test
    void rejects_artifact_or_visibility_marker_drift() throws Exception {
        Path brokenArtifact = copyFixture("broken-artifact");
        Files.writeString(
                brokenArtifact.resolve("masks_list_generated.csv"),
                "unexpected",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        Path brokenMarker = copyFixture("broken-marker");
        Files.writeString(brokenMarker.resolve("_SUCCESS"), "0".repeat(64) + "\n", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> consumer.readSlice(brokenArtifact, Map.of("masks", MASKS_HEADER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Artifact checksum mismatch");
        assertThatThrownBy(() -> consumer.readSlice(brokenMarker, Map.of("masks", MASKS_HEADER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_SUCCESS does not bind manifest.json");
    }

    private Path copyFixture(String targetName) throws Exception {
        Path target = Files.createDirectory(tempDir.resolve(targetName));
        for (String member : List.of("manifest.json", "masks_list_generated.csv", "_SUCCESS")) {
            Files.copy(fixture().resolve(member), target.resolve(member));
        }
        return target;
    }

    private Path fixture() {
        return Path.of("src/test/resources/consumer/export-slice-v1");
    }
}
