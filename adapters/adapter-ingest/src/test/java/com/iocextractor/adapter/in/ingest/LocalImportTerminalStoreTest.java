package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocalImportTerminalStoreTest {

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
        assertThat(replay.reference().value()).startsWith(LocalManagedImportSourceLifecycle.REFERENCE_PREFIX);
        store.delete(terminal);
        assertThat(temporaryDirectory.resolve("terminal")).isEmptyDirectory();
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

    private Path onlyChild(Path root) throws Exception {
        try (var children = Files.list(root)) {
            return children.findFirst().orElseThrow();
        }
    }
}
