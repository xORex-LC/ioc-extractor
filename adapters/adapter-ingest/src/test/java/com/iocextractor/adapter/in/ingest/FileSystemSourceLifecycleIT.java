package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.ClaimedSource;
import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class FileSystemSourceLifecycleIT {

    @TempDir
    Path tempDir;

    @Test
    void candidateEvidenceRejectsDirectory() {
        var reader = new FileDocumentCandidateEvidenceReader();

        assertThatThrownBy(() -> reader.read(tempDir))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Document candidate is not a regular file");
    }

    @Test
    void candidateEvidenceRejectsSymbolicLinkWithoutFollowingItsTarget() throws Exception {
        Path target = Files.writeString(tempDir.resolve("target.html"), "ioc");
        Path link = Files.createSymbolicLink(tempDir.resolve("candidate.html"), target.getFileName());
        var reader = new FileDocumentCandidateEvidenceReader();

        assertThatThrownBy(() -> reader.read(link))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Document candidate is not a regular file");
    }

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
    void adapterRetryResumesAdmissionAfterCandidatePathWasClaimed() throws Exception {
        Path processing = tempDir.resolve("processing-admit-retry");
        var lifecycle = new FileSystemSourceLifecycle(
                processing, tempDir.resolve("done-admit-retry"), tempDir.resolve("failed-admit-retry"));
        var registrations = new MemoryRegistrationStore();
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC);
        Path source = Files.writeString(tempDir.resolve("admit-retry.html"), "ioc-data");
        var handler = handler(tempDir.resolve("admission-admit-retry"),
                lifecycle, registrations, clock);
        ObservationId id = new ObservationId("delivery-admit-retry");

        var first = handler.admit(source, id, clock.instant());
        var retried = handler.admit(source, id, clock.instant().plusSeconds(10));

        assertThat(source).doesNotExist();
        assertThat(retried).isEqualTo(first);
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

    @ParameterizedTest
    @NullSource
    @EnumSource(IngestionStatus.class)
    void restartReconcilesLinkedAdmissionWithDurableIngestionStatusOrItsAbsence(
            IngestionStatus status)
            throws Exception {
        Path processing = tempDir.resolve("processing-ledger-" + status);
        var lifecycle = new FileSystemSourceLifecycle(
                processing, tempDir.resolve("done-ledger-" + status),
                tempDir.resolve("failed-ledger-" + status));
        var registrations = new MemoryRegistrationStore();
        Clock clock = Clock.fixed(Instant.parse("2026-09-25T13:00:00Z"), ZoneOffset.UTC);
        var journal = new FileDocumentAdmissionJournal(
                tempDir.resolve("admission-ledger-" + status));
        var service = new DocumentAdmissionService(journal, registrations, clock);
        var initial = new OrderedDocumentAdmissionHandler(
                service, lifecycle, new FileDocumentCandidateEvidenceReader(),
                new FileSourceHasher());
        ObservationId id = new ObservationId("delivery-ledger-" + status);
        Path source = Files.writeString(tempDir.resolve("ledger-" + status + ".html"), "ioc-data");
        var admitted = initial.admit(source, id, clock.instant());
        var record = status == null ? null : new IngestionRecord(
                id, admitted.source().key(), status, admitted.source().originalPath(),
                admitted.source().processingPath(), null, admitted.source().detectedAt(),
                clock.instant(), status == IngestionStatus.FAILED ? "failed" : null);
        var recovering = new OrderedDocumentAdmissionHandler(
                service, lifecycle, new FileDocumentCandidateEvidenceReader(),
                new FileSourceHasher(), new SnapshotLedger(record));

        var recovered = recovering.recover(10);

        if (status == null || status == IngestionStatus.CLAIMED) {
            assertThat(recovered).singleElement().isEqualTo(admitted);
            assertThat(service.find(id).orElseThrow().terminalOutcome()).isEmpty();
        } else {
            assertThat(recovered).isEmpty();
            DocumentTerminalOutcome expected = status == IngestionStatus.SOURCE_ARCHIVED
                    ? DocumentTerminalOutcome.SUCCEEDED : DocumentTerminalOutcome.REJECTED;
            assertThat(service.find(id).orElseThrow().terminalOutcome()).contains(expected);
            assertThat(service.find(id).orElseThrow().registrationFinalized()).isTrue();
        }
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

    private record SnapshotLedger(IngestionRecord record) implements IngestionLedger {

        @Override
        public Optional<IngestionRecord> find(ObservationId observationId) {
            return record != null && record.observationId().equals(observationId)
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public IngestionLedgerTransition markClaimed(SourceUnit unit) {
            throw new AssertionError("recovery must not claim through the ingestion ledger");
        }

        @Override
        public IngestionLedgerTransition markSourceArchived(
                ObservationId observationId, Path archivedPath) {
            throw new AssertionError("recovery must not archive through the ingestion ledger");
        }

        @Override
        public IngestionLedgerTransition markFailed(
                ObservationId observationId, SourceKey key, String reason) {
            throw new AssertionError("recovery must not fail through the ingestion ledger");
        }

        @Override
        public List<IngestionRecord> findIncomplete() {
            throw new AssertionError("document admission recovery uses point lookup");
        }
    }
}
