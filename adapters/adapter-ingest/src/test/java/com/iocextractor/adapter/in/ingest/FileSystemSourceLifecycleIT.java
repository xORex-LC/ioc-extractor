package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.ClaimedSource;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class FileSystemSourceLifecycleIT {

    @TempDir
    Path tempDir;

    @Test
    void claims_archives_and_fails_sources_with_error_sidecar() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"),
                tempDir.resolve("done"),
                tempDir.resolve("failed"));
        var key = new SourceKey("ABC123");
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");

        var unit = lifecycle.claim(source, key, Instant.parse("2026-06-22T00:00:00Z"));
        assertThat(source).doesNotExist();
        assertThat(unit.processingPath()).exists();
        assertThat(unit.processingPath().getFileName().toString())
                .isEqualTo("abc123-source.html");

        Path archived = lifecycle.archive(unit);
        assertThat(archived).exists();
        assertThat(archived.getFileName().toString())
                .isEqualTo("abc123-source.html");
        assertThat(unit.processingPath()).doesNotExist();

        Path failedSource = Files.writeString(tempDir.resolve("source2.html"), "ioc");
        var failedUnit = lifecycle.claim(failedSource, key, Instant.parse("2026-06-22T00:00:00Z"));
        Path failed = lifecycle.fail(failedUnit, "broken");
        assertThat(failed).exists();
        assertThat(failed.getFileName().toString())
                .isEqualTo("abc123-source2.html");
        assertThat(failed.resolveSibling(failed.getFileName() + ".error"))
                .hasContent("broken");
    }

    @Test
    void lists_processing_sources_as_recovery_candidates() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"),
                tempDir.resolve("done"),
                tempDir.resolve("failed"));
        Files.createDirectories(tempDir.resolve("processing"));
        Files.writeString(tempDir.resolve("processing/abc123-source.html"), "ioc");
        Files.writeString(tempDir.resolve("processing/unkeyed.html"), "ignored");

        assertThat(lifecycle.findProcessingSources())
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.key().value()).isEqualTo("abc123");
                    assertThat(source.processingPath().getFileName().toString()).isEqualTo("abc123-source.html");
                });
    }

    @Test
    void repeated_content_is_owned_by_distinct_recoverable_observation_names() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"),
                tempDir.resolve("done"),
                tempDir.resolve("failed"));
        var key = new SourceKey("abc123");
        Path first = Files.writeString(tempDir.resolve("first.html"), "ioc");
        Path second = Files.writeString(tempDir.resolve("second.html"), "ioc");

        var firstUnit = lifecycle.claim(
                first, new ObservationId("delivery-1"), key, Instant.EPOCH);
        var secondUnit = lifecycle.claim(
                second, new ObservationId("delivery-2"), key, Instant.EPOCH);

        assertThat(firstUnit.processingPath()).isNotEqualTo(secondUnit.processingPath());
        assertThat(lifecycle.findProcessingSources())
                .extracting(source -> source.observationId().value())
                .containsExactlyInAnyOrder("delivery-1", "delivery-2");
    }

    @Test
    void rejects_empty_lifecycle_directory_paths_at_construction() {
        Path empty = Path.of("");
        Path directory = Path.of("directory");

        assertThatThrownBy(() -> new FileSystemSourceLifecycle(empty, directory, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("processingDir must not be an empty path");
        assertThatThrownBy(() -> new FileSystemSourceLifecycle(directory, empty, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("doneDir must not be an empty path");
        assertThatThrownBy(() -> new FileSystemSourceLifecycle(directory, directory, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failedDir must not be an empty path");
    }

    @Test
    void existing_target_is_never_replaced() throws Exception {
        Path processing = tempDir.resolve("processing");
        Files.createDirectories(processing);
        Files.writeString(processing.resolve("abc123-source.html"), "owned");
        Path source = Files.writeString(tempDir.resolve("source.html"), "new");
        var lifecycle = new FileSystemSourceLifecycle(
                processing, tempDir.resolve("done"), tempDir.resolve("failed"));

        assertThatThrownBy(() -> lifecycle.claim(
                source, new SourceKey("abc123"), Instant.EPOCH))
                .hasMessageContaining("target already exists");

        assertThat(source).hasContent("new");
        assertThat(processing.resolve("abc123-source.html")).hasContent("owned");
    }

    @Test
    void unsupported_atomic_move_has_no_non_atomic_fallback() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");
        var ownership = new StrictAtomicFileOwnership((ignoredSource, ignoredTarget) -> {
            throw new AtomicMoveNotSupportedException("source", "target", "unsupported");
        });
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"),
                tempDir.resolve("failed"), ownership);

        assertThatThrownBy(() -> lifecycle.claim(
                source, new SourceKey("abc123"), Instant.EPOCH))
                .hasMessageContaining("not supported");

        assertThat(source).exists();
    }

    @Test
    void sealedClaimIsUnaffectedByProducerOpenFileDescriptor() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        ObservationId observationId = new ObservationId("delivery-open-writer");
        Path source = Files.writeString(tempDir.resolve("source-open.html"), "original");
        ClaimedSource claimed = lifecycle.claimBeforeHash(source, observationId, Instant.EPOCH);

        try (FileChannel producerHandle = FileChannel.open(claimed.processingPath(), StandardOpenOption.WRITE)) {
            ClaimedSource sealed = lifecycle.sealClaim(claimed);
            producerHandle.position(0);
            producerHandle.write(ByteBuffer.wrap("mutated!".getBytes(StandardCharsets.UTF_8)));
            producerHandle.force(true);

            assertThat(sealed.processingPath()).hasContent("original");
            assertThat(claimed.processingPath()).doesNotExist();
            assertThat(lifecycle.sealClaim(claimed).processingPath()).isEqualTo(sealed.processingPath());
        }
    }

    @Test
    void fileJournalRestartAdoptsTheSameSealedOccurrenceAndOrder() throws Exception {
        Path processing = tempDir.resolve("processing");
        var lifecycle = new FileSystemSourceLifecycle(
                processing, tempDir.resolve("done"), tempDir.resolve("failed"));
        var registrations = new MemoryRegistrationStore();
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC);
        Path journalPath = tempDir.resolve("admission-journal");
        var firstHandler = handler(journalPath, lifecycle, registrations, clock);
        Path source = Files.writeString(tempDir.resolve("restart.html"), "ioc-data");

        var admitted = firstHandler.admit(source, new ObservationId("delivery-restart"), clock.instant());
        var recovered = handler(journalPath, lifecycle, registrations, clock).recover(10);

        assertThat(recovered).singleElement().satisfies(value -> {
            assertThat(value.registration()).isEqualTo(admitted.registration());
            assertThat(value.source().key()).isEqualTo(admitted.source().key());
            assertThat(value.source().processingPath()).isEqualTo(admitted.source().processingPath());
        });
        assertThat(registrations.nextOrder).isEqualTo(2);
    }

    @Test
    void restartAfterTokenRenameLinksTheExistingClaimWithoutReregistering() throws Exception {
        Path processing = tempDir.resolve("processing-claim-crash");
        var lifecycle = new FileSystemSourceLifecycle(
                processing, tempDir.resolve("done-claim-crash"), tempDir.resolve("failed-claim-crash"));
        var registrations = new MemoryRegistrationStore();
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC);
        Path journalPath = tempDir.resolve("admission-claim-crash");
        var journal = new FileDocumentAdmissionJournal(journalPath);
        var service = new DocumentAdmissionService(journal, registrations, clock);
        var evidenceReader = new FileDocumentCandidateEvidenceReader();
        ObservationId id = new ObservationId("delivery-claim-crash");
        Path source = Files.writeString(tempDir.resolve("claim-crash.html"), "ioc-data");
        Path claimPath = lifecycle.prehashClaimPath(source, id);
        var reservation = new DocumentAdmissionReservation(
                id, source, evidenceReader.read(source), claimPath, clock.instant());
        RegisteredObservation registration = service.admit(reservation).registration().orElseThrow();
        lifecycle.claimBeforeHash(source, id, clock.instant());

        var recovered = handler(journalPath, lifecycle, registrations, clock).recover(10);

        assertThat(recovered).singleElement().satisfies(value -> {
            assertThat(value.registration()).isEqualTo(registration);
            assertThat(value.source().processingPath()).exists();
        });
        assertThat(registrations.nextOrder).isEqualTo(2);
    }

    private OrderedDocumentAdmissionHandler handler(Path journalPath,
                                                    FileSystemSourceLifecycle lifecycle,
                                                    ObservationRegistrationStore registrations,
                                                    Clock clock) {
        return new OrderedDocumentAdmissionHandler(
                new DocumentAdmissionService(
                        new FileDocumentAdmissionJournal(journalPath), registrations, clock),
                lifecycle, new FileDocumentCandidateEvidenceReader(), new FileSourceHasher());
    }

    private static final class MemoryRegistrationStore implements ObservationRegistrationStore {
        private static final String NAMESPACE = "0123456789abcdef0123456789abcdef";
        private final Map<ObservationId, RegisteredObservation> values = new LinkedHashMap<>();
        private long nextOrder = 1;

        @Override
        public RegisteredObservation registerNew(ObservationId id, ObservationOrigin origin) {
            return values.computeIfAbsent(id, ignored -> new RegisteredObservation(
                    id, NAMESPACE, new ObservationOrder(nextOrder++), origin));
        }

        @Override
        public RegisteredObservation resume(ObservationId id, String expectedNamespace) {
            if (!NAMESPACE.equals(expectedNamespace) || !values.containsKey(id)) {
                throw new IllegalStateException("Missing registered observation on recovery");
            }
            return values.get(id);
        }

        @Override
        public void markTerminal(ObservationId id, String expectedNamespace) {
            resume(id, expectedNamespace);
        }

        @Override
        public boolean purgeTerminal(RegisteredObservation registration) {
            return values.remove(registration.observationId(), registration);
        }
    }
}
