package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class LocalImportTerminalStoreIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesIdempotentAtomicUnitWithoutEchoingInputValuesOrPaths() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip;description\n192.0.2.1;secret-value\n");
        ImportSnapshotReference reference = new ImportSnapshotReference("snapshot:test");
        LocalImportTerminalStore store = new LocalImportTerminalStore(
                temporaryDirectory.resolve("terminal"), temporaryDirectory.resolve("quarantine"),
                temporaryDirectory.resolve("snapshots"), ignored -> source, 1024);
        PublishImportReportCommand command = new PublishImportReportCommand(
                new ImportDeliveryId("delivery-a"), new ImportSourceId("source-a"), reference,
                Optional.empty(), ImportTerminalOutcome.COMPLETED_WITH_ERRORS,
                1, 1, 1, Set.of("ip_list"), List.of("IMPORT.INPUT_INVALID"),
                List.of(new ImportRowIssue(3, "ip_list", "IMPORT.FIELD_INVALID")));

        store.publish(command);
        store.publish(command);

        Path unit = onlyChild(temporaryDirectory.resolve("terminal"));
        assertThat(unit.resolve("source.csv")).hasSameTextualContentAs(source);
        String report = Files.readString(unit.resolve("report.json"));
        assertThat(report)
                .contains("\"deliveryId\":\"delivery-a\"")
                .contains("\"code\":\"IMPORT.FIELD_INVALID\"")
                .doesNotContain("192.0.2.1", "secret-value", source.toString(), reference.value());
        try (var children = Files.list(temporaryDirectory.resolve("terminal"))) {
            assertThat(children.toList())
                    .noneMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void publishesCompleteEscapedReportToOutcomeSpecificQuarantine() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportSnapshotReference reference = new ImportSnapshotReference("snapshot:test");
        LocalImportTerminalStore store = store(ignored -> source, 1024);
        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("contract-\"\\\b\f\n\r\t\u0001"), 7,
                new ImportContractFingerprint("a".repeat(64)));
        PublishImportReportCommand command = new PublishImportReportCommand(
                new ImportDeliveryId("delivery-rejected"),
                new ImportSourceId("source-\"\\\b\f\n\r\t\u0001"), reference,
                Optional.of(contract), ImportTerminalOutcome.REJECTED,
                2, 3, 4, Set.of("masks", "ip_list"),
                List.of("IMPORT.ONE", "IMPORT.TWO"),
                List.of(
                        new ImportRowIssue(4, null, "IMPORT.NULL_ARTIFACT"),
                        new ImportRowIssue(9, "mask\"\\", "IMPORT.CONTROL\nCODE")));

        store.publish(command);

        Path unit = onlyChild(temporaryDirectory.resolve("quarantine"));
        assertThat(temporaryDirectory.resolve("terminal")).isEmptyDirectory();
        assertThat(unit.resolve("source.csv")).hasSameTextualContentAs(source);
        assertThat(Files.readString(unit.resolve("report.json")))
                .contains("\"sourceId\":\"source-\\\"\\\\\\b\\f\\n\\r\\t\\u0001\"")
                .contains("\"contractId\":\"contract-\\\"\\\\\\b\\f\\n\\r\\t\\u0001\"")
                .contains("\"contractVersion\":7")
                .contains("\"contractFingerprint\":\"" + "a".repeat(64) + "\"")
                .contains("\"affectedArtifacts\":[\"ip_list\",\"masks\"]")
                .contains("\"deliveryCodes\":[\"IMPORT.ONE\",\"IMPORT.TWO\"]")
                .contains("{\"row\":4,\"artifact\":null,\"code\":\"IMPORT.NULL_ARTIFACT\"}")
                .contains("{\"row\":9,\"artifact\":\"mask\\\"\\\\\","
                        + "\"code\":\"IMPORT.CONTROL\\nCODE\"}");
    }

    @Test
    void retainedTerminalSourceCreatesASeparateReplaySnapshotAndCanBePurged() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportDeliveryId terminal = new ImportDeliveryId("delivery-terminal");
        LocalImportTerminalStore store = new LocalImportTerminalStore(
                temporaryDirectory.resolve("terminal"), temporaryDirectory.resolve("quarantine"),
                temporaryDirectory.resolve("snapshots"), ignored -> source, 1024);
        store.publish(new PublishImportReportCommand(
                terminal, new ImportSourceId("source-a"), new ImportSnapshotReference("snapshot:test"),
                Optional.empty(), ImportTerminalOutcome.SUCCEEDED, 1, 0, 1,
                Set.of("ip_list"), List.of(), List.of()));

        var replay = store.materializeReplay(terminal, new ImportDeliveryId("delivery-replay"));

        assertThat(replay.size()).isEqualTo(Files.size(source));
        assertThat(replay.reference().value())
                .startsWith(LocalFilesystemImportSnapshotStore.REFERENCE_PREFIX);
        store.delete(terminal);
        assertThat(temporaryDirectory.resolve("terminal")).isEmptyDirectory();
    }

    @Test
    void rejectsReplayWhenRetainedEvidenceIsMissingAmbiguousOrOversized() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportDeliveryId terminal = new ImportDeliveryId("delivery-terminal");
        ImportDeliveryId replay = new ImportDeliveryId("delivery-replay");
        LocalImportTerminalStore store = store(ignored -> source, 4);

        assertThatThrownBy(() -> store.materializeReplay(terminal, replay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retained import terminal unit is missing or ambiguous");

        store.publish(command(terminal, ImportTerminalOutcome.SUCCEEDED));
        copyUnit(onlyChild(temporaryDirectory.resolve("terminal")),
                temporaryDirectory.resolve("quarantine"));

        assertThatThrownBy(() -> store.materializeReplay(terminal, replay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retained import terminal unit is missing or ambiguous");

        store.delete(terminal);
        store.publish(command(terminal, ImportTerminalOutcome.SUCCEEDED));

        assertThatThrownBy(() -> store.materializeReplay(terminal, replay))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import snapshot exceeds configured byte limit");
    }

    @Test
    void rejectsNonRegularSnapshotWithoutPublishingTerminalEvidence() throws Exception {
        Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("snapshot-directory"));
        Path validSource = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(validSource, "ip\n192.0.2.1\n");
        AtomicReference<Path> resolved = new AtomicReference<>(sourceDirectory);
        LocalImportTerminalStore store = store(ignored -> resolved.get(), 1024);
        PublishImportReportCommand command = command(
                new ImportDeliveryId("delivery-invalid-snapshot"), ImportTerminalOutcome.SUCCEEDED);

        assertThatThrownBy(() -> store.publish(command))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import snapshot is not a regular protected file");

        try (var children = Files.list(temporaryDirectory.resolve("terminal"))) {
            assertThat(children.toList())
                    .noneMatch(path -> !path.getFileName().toString().endsWith(".part"));
        }

        resolved.set(validSource);
        store.publish(command);

        Path unit = onlyChild(temporaryDirectory.resolve("terminal"));
        assertThat(unit.resolve("source.csv")).hasSameTextualContentAs(validSource);
        try (var children = Files.list(temporaryDirectory.resolve("terminal"))) {
            assertThat(children.toList())
                    .noneMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void archivesTheSourceReportPairAtomicallyAndIdempotently() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportDeliveryId delivery = new ImportDeliveryId("delivery-archive");
        LocalImportTerminalStore store = new LocalImportTerminalStore(
                temporaryDirectory.resolve("terminal"), temporaryDirectory.resolve("quarantine"),
                temporaryDirectory.resolve("snapshots"), ignored -> source, 1024);
        store.publish(new PublishImportReportCommand(
                delivery, new ImportSourceId("source-a"), new ImportSnapshotReference("snapshot:test"),
                Optional.empty(), ImportTerminalOutcome.REJECTED, 0, 1, 0,
                Set.of(), List.of("IMPORT.INPUT_INVALID"), List.of()));

        Path archive = temporaryDirectory.resolve("archive");
        store.archive(delivery, archive);
        store.archive(delivery, archive);

        Path unit = onlyChild(archive.resolve("quarantine"));
        assertThat(unit.resolve("source.csv")).hasSameTextualContentAs(source);
        assertThat(unit.resolve("report.json")).isRegularFile();
        assertThat(temporaryDirectory.resolve("quarantine")).isEmptyDirectory();
    }

    @Test
    void rejectsMissingAmbiguousOrConflictingArchiveEvidence() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportDeliveryId delivery = new ImportDeliveryId("delivery-archive-conflict");
        LocalImportTerminalStore store = store(ignored -> source, 1024);
        Path archive = temporaryDirectory.resolve("archive");

        assertThatThrownBy(() -> store.archive(delivery, archive))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import terminal archive source is missing or ambiguous");

        store.publish(command(delivery, ImportTerminalOutcome.SUCCEEDED));
        copyUnit(onlyChild(temporaryDirectory.resolve("terminal")),
                temporaryDirectory.resolve("quarantine"));

        assertThatThrownBy(() -> store.archive(delivery, archive))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import terminal archive source is missing or ambiguous");

        store.delete(delivery);
        store.publish(command(delivery, ImportTerminalOutcome.SUCCEEDED));
        store.archive(delivery, archive);
        store.publish(command(delivery, ImportTerminalOutcome.SUCCEEDED));

        assertThatThrownBy(() -> store.archive(delivery, archive))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import terminal archive source is missing or ambiguous");
    }

    @Test
    void rejectsArchiveRootsThatOverlapManagedStorage() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportDeliveryId delivery = new ImportDeliveryId("delivery-overlap");
        LocalImportTerminalStore store = store(ignored -> source, 1024);
        store.publish(command(delivery, ImportTerminalOutcome.SUCCEEDED));

        for (Path overlapping : List.of(
                temporaryDirectory.resolve("terminal/archive"),
                temporaryDirectory.resolve("quarantine/archive"),
                temporaryDirectory.resolve("snapshots/archive"))) {
            assertThatThrownBy(() -> store.archive(delivery, overlapping))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Import archive directory must be disjoint from managed roots");
        }
    }

    @Test
    void deletesBothTerminalOutcomesIdempotently() throws Exception {
        Path source = temporaryDirectory.resolve("snapshot.csv");
        Files.writeString(source, "ip\n192.0.2.1\n");
        ImportDeliveryId delivery = new ImportDeliveryId("delivery-delete");
        LocalImportTerminalStore store = store(ignored -> source, 1024);
        store.publish(command(delivery, ImportTerminalOutcome.SUCCEEDED));
        store.publish(command(delivery, ImportTerminalOutcome.REJECTED));

        store.delete(delivery);
        store.delete(delivery);

        assertThat(temporaryDirectory.resolve("terminal")).isEmptyDirectory();
        assertThat(temporaryDirectory.resolve("quarantine")).isEmptyDirectory();
    }

    @Test
    void validatesSnapshotCapacityAtConstruction() {
        assertThatThrownBy(() -> store(ignored -> temporaryDirectory.resolve("snapshot.csv"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum import snapshot bytes must be positive");
    }

    private LocalImportTerminalStore store(
            java.util.function.Function<ImportSnapshotReference, Path> snapshots,
            long maximumSnapshotBytes) {
        return new LocalImportTerminalStore(
                temporaryDirectory.resolve("terminal"), temporaryDirectory.resolve("quarantine"),
                temporaryDirectory.resolve("snapshots"), snapshots, maximumSnapshotBytes);
    }

    private PublishImportReportCommand command(
            ImportDeliveryId deliveryId,
            ImportTerminalOutcome outcome) {
        return new PublishImportReportCommand(
                deliveryId, new ImportSourceId("source-a"),
                new ImportSnapshotReference("snapshot:test"), Optional.empty(), outcome,
                1, 0, 1, Set.of("ip_list"), List.of(), List.of());
    }

    private void copyUnit(Path source, Path destinationRoot) throws Exception {
        Path destination = Files.createDirectory(destinationRoot.resolve(source.getFileName()));
        Files.copy(source.resolve("source.csv"), destination.resolve("source.csv"));
        Files.copy(source.resolve("report.json"), destination.resolve("report.json"));
    }

    private Path onlyChild(Path root) throws Exception {
        try (var children = Files.list(root)) {
            return children.findFirst().orElseThrow();
        }
    }
}
