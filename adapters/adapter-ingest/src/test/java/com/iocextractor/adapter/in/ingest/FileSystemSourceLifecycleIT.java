package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.adapter.out.maintenance.FileSystemRetentionStore;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.ClaimedSource;
import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentRejection;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.maintenance.RetentionEntry;
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
    void grownClaimStopsAtTheAdmittedByteAllowanceAndRemovesPartialCopy() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        Path source = Files.writeString(tempDir.resolve("growing.html"), "original");
        var claimed = lifecycle.claimBeforeHash(source, new ObservationId("growing-delivery"), Instant.EPOCH);
        Files.writeString(claimed.processingPath(), "original-with-extra-bytes");

        assertThatThrownBy(() -> lifecycle.sealClaim(claimed, 8))
                .hasMessageContaining("size changed after bounded admission");
        assertThat(claimed.processingPath()).hasContent("original-with-extra-bytes");
        assertThat(Path.of(claimed.processingPath() + ".sealing")).doesNotExist();
        assertThat(Path.of(claimed.processingPath() + ".sealed")).doesNotExist();
    }

    @Test
    void sealedClaimIsUnaffectedByProducerOpenFileDescriptor() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        ObservationId observationId = new ObservationId("delivery-open-writer");
        Path source = Files.writeString(tempDir.resolve("source-open.html"), "original");
        ClaimedSource claimed = lifecycle.claimBeforeHash(source, observationId, Instant.EPOCH);

        try (FileChannel producerHandle = FileChannel.open(claimed.processingPath(), StandardOpenOption.WRITE)) {
            ClaimedSource sealed = lifecycle.sealClaim(claimed, Files.size(claimed.processingPath()));
            producerHandle.position(0);
            producerHandle.write(ByteBuffer.wrap("mutated!".getBytes(StandardCharsets.UTF_8)));
            producerHandle.force(true);

            assertThat(sealed.processingPath()).hasContent("original");
            assertThat(claimed.processingPath()).doesNotExist();
            assertThat(lifecycle.sealClaim(claimed, Files.size(sealed.processingPath())).processingPath())
                    .isEqualTo(sealed.processingPath());
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
        var recovered = List.of(handler(journalPath, lifecycle, registrations, clock)
                .finish(admitted.registration().observationId()));

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
        Files.writeString(source, "new delivery");

        var recovered = List.of(handler(journalPath, lifecycle, registrations, clock).finish(id));

        assertThat(recovered).singleElement().satisfies(value -> {
            assertThat(value.registration()).isEqualTo(registration);
            assertThat(value.source().processingPath()).hasContent("ioc-data");
        });
        assertThat(source).hasContent("new delivery");
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
                new FileSourceHasher(), new FileIngestionLedger(tempDir.resolve("ingestion-ledger"), clock));
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

        var pending = recovering.pending(10);

        if (status == IngestionStatus.SOURCE_ARCHIVED) {
            assertThat(pending).isEmpty();
            assertThat(service.find(id).orElseThrow().terminalOutcome()).contains(DocumentTerminalOutcome.SUCCEEDED);
        } else {
            assertThat(pending).singleElement().satisfies(value -> {
                assertThat(recovering.promotionUncertain(value)).isEqualTo(status != null);
                assertThat(value.terminalOutcome()).isEmpty();
            });
            assertThat(admitted.source().processingPath()).exists();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sidecar", "source-ledger", "registration"})
    void restartRepairsInterruptedDisposition(String interruptedAt) throws Exception {
        Path journalPath = tempDir.resolve("admissions");
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        var ledger = new FileIngestionLedger(tempDir.resolve("ledger"), Clock.systemUTC());
        var registrations = new MemoryRegistrationStore();
        var reader = new FileDocumentCandidateEvidenceReader();
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var service = new DocumentAdmissionService(new FileDocumentAdmissionJournal(journalPath), registrations, clock);
        var handler = new OrderedDocumentAdmissionHandler(service, lifecycle, reader, new FileSourceHasher(), ledger);
        var id = new ObservationId("interrupted-rejection");
        Path original = Files.writeString(tempDir.resolve("bad.html"), "invalid document");
        var linked = handler.admit(original, id, clock.instant());
        var current = handler.beginRejection(service.find(id).orElseThrow(),
                DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED);
        var intent = current.rejection().orElseThrow();
        Path sidecar = intent.target().resolveSibling(intent.target().getFileName() + ".error");
        Files.createDirectories(intent.target().getParent());
        if ("sidecar".equals(interruptedAt)) {
            Files.move(intent.source(), intent.target());
            Files.writeString(sidecar, "partial");
            assertThat(ledger.find(id)).isEmpty();
        } else if ("source-ledger".equals(interruptedAt)) {
            Files.move(intent.source(), intent.target());
            Files.writeString(sidecar, "DOCUMENT_PREPARATION_FAILED\n");
            ledger.recordRejected(linked.source(), intent.target(), intent.code().name());
        } else {
            registrations.failTerminalMark = true;
            assertThatThrownBy(() -> handler.finishRejection(current))
                    .hasMessage("Synthetic registration finalization failure");
            assertThat(service.find(id).orElseThrow().registrationFinalized()).isFalse();
            registrations.failTerminalMark = false;
        }

        var reopened = new DocumentAdmissionService(new FileDocumentAdmissionJournal(journalPath), registrations, clock);
        var restarted = new OrderedDocumentAdmissionHandler(reopened, lifecycle, reader, new FileSourceHasher(), ledger);
        var retention = new FileSystemRetentionStore(restarted::pinsRejectionLeaf);
        assertThat(retention.list(intent.target().getParent())).isEmpty();
        var remaining = restarted.pending(10);
        if (!remaining.isEmpty()) {
            restarted.finishRejection(remaining.getFirst());
        }

        assertThat(intent.target()).hasContent("invalid document");
        assertThat(sidecar).hasContent("DOCUMENT_PREPARATION_FAILED\n");
        assertThat(intent.source()).doesNotExist();
        assertThat(ledger.find(id)).get().satisfies(record ->
                assertThat(record.matchesRejection(linked.source(), intent.target(), intent.code().name())).isTrue());
        assertThat(restarted.pending(10)).isEmpty();
        assertThat(retention.list(intent.target().getParent())).hasSize(2);
        assertThat(reopened.find(id)).get().satisfies(value -> {
            assertThat(value.registrationFinalized()).isTrue();
            assertThat(value.terminalOutcome()).contains(DocumentTerminalOutcome.REJECTED);
        });
        try (var files = Files.list(intent.target().getParent())) {
            assertThat(files).containsExactlyInAnyOrder(intent.target(), sidecar);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ambiguousRejectionKeepsEvidenceAndDoesNotComplete(boolean occupiedTarget) throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        var service = new DocumentAdmissionService(new FileDocumentAdmissionJournal(tempDir.resolve("admissions")),
                new MemoryRegistrationStore(), Clock.systemUTC());
        var handler = new OrderedDocumentAdmissionHandler(
                service, lifecycle, new FileDocumentCandidateEvidenceReader(), new FileSourceHasher(),
                new FileIngestionLedger(tempDir.resolve("ingestion-ledger"), Clock.systemUTC()));
        var id = new ObservationId("ambiguous-rejection");
        handler.admit(Files.writeString(tempDir.resolve("bad.html"), "own"), id, Instant.now());
        var current = handler.beginRejection(service.find(id).orElseThrow(),
                DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED);
        var intent = current.rejection().orElseThrow();
        if (occupiedTarget) {
            Files.createDirectories(intent.target().getParent());
            Files.writeString(intent.target(), "foreign");
        } else {
            Files.delete(intent.source());
        }

        assertThatThrownBy(() -> handler.finishRejection(current)).isInstanceOf(RuntimeException.class);
        assertThat(service.find(id).orElseThrow().terminalOutcome()).isEmpty();
        if (occupiedTarget) {
            assertThat(intent.source()).hasContent("own");
            assertThat(intent.target()).hasContent("foreign");
        } else {
            assertThat(intent.target()).doesNotExist();
        }
    }

    @Test
    void restartDoesNotClaimReplacementAtReservedInboxPath() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        var service = new DocumentAdmissionService(new FileDocumentAdmissionJournal(tempDir.resolve("admissions")),
                new MemoryRegistrationStore(), Clock.systemUTC());
        var reader = new FileDocumentCandidateEvidenceReader();
        var handler = new OrderedDocumentAdmissionHandler(service, lifecycle, reader, new FileSourceHasher(),
                new FileIngestionLedger(tempDir.resolve("ingestion-ledger"), Clock.systemUTC()));
        var id = new ObservationId("replaced-candidate");
        Path inbox = Files.writeString(tempDir.resolve("source.html"), "first");
        Path claim = lifecycle.prehashClaimPath(inbox, id);
        service.admit(new DocumentAdmissionReservation(id, inbox, reader.read(inbox), claim, Instant.now()));
        Path saved = Files.move(inbox, tempDir.resolve("saved"));
        Files.writeString(inbox, "replacement");

        assertThatThrownBy(() -> handler.finish(id)).hasMessage("CANDIDATE_REPLACED");
        assertThat(inbox).hasContent("replacement");
        assertThat(saved).hasContent("first");
        assertThat(claim).doesNotExist();
        assertThat(service.find(id).orElseThrow().claimedEvidence()).isEmpty();
    }

    @Test
    void pendingReconcilesTerminalAdmissionAfterRegistrationFinalizationFails() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        var registrations = new MemoryRegistrationStore();
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        Path journalPath = tempDir.resolve("admission-journal");
        var journal = new FileDocumentAdmissionJournal(journalPath);
        var service = new DocumentAdmissionService(journal, registrations, clock);
        var id = new ObservationId("delivery-terminal-recovery");
        Path source = Files.writeString(tempDir.resolve("terminal-recovery.html"), "ioc-data");
        var handler = handler(journalPath, lifecycle, registrations, clock);
        var admitted = handler.admit(source, id, clock.instant());

        registrations.failTerminalMark = true;
        assertThatThrownBy(() -> service.complete(id, DocumentTerminalOutcome.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Synthetic registration finalization failure");
        var interrupted = service.find(id).orElseThrow();
        assertThat(interrupted.terminalOutcome()).contains(DocumentTerminalOutcome.SUCCEEDED);
        assertThat(interrupted.registrationFinalized()).isFalse();

        registrations.failTerminalMark = false;
        var restarted = handler(journalPath, lifecycle, registrations, clock);
        assertThat(restarted.pending(10)).isEmpty();
        var completed = service.find(id).orElseThrow();
        assertThat(completed.terminalOutcome()).contains(DocumentTerminalOutcome.SUCCEEDED);
        assertThat(completed.registration()).contains(admitted.registration());
        assertThat(completed.registrationFinalized()).isTrue();
        assertThat(restarted.pending(10)).isEmpty();
        assertThat(service.find(id)).contains(completed);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"valid", "wrong-digest", "new-mtime", "new-size"})
    void legacySealedClaimRestartsOnlyWithCoherentEvidence(String scenario) throws Exception {
        var fixture = new RejectionFixture();
        var claimed = fixture.handler.claim(fixture.inbox, fixture.id, fixture.clock.instant());
        var sealed = fixture.lifecycle.sealClaim(new ClaimedSource(fixture.id, fixture.inbox,
                claimed.claimPath(), fixture.clock.instant()), claimed.candidateEvidence().size());
        var key = scenario.equals("wrong-digest") ? new SourceKey("other")
                : new FileSourceHasher().sha256(sealed.processingPath());
        fixture.service.link(fixture.id, key);
        if (scenario.equals("new-mtime")) {
            Files.setLastModifiedTime(sealed.processingPath(),
                    java.nio.file.attribute.FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
        } else if (scenario.equals("new-size")) {
            Files.writeString(sealed.processingPath(), "different size");
        }

        if (scenario.equals("valid")) {
            assertThat(fixture.handler.finish(fixture.id).source().key()).isEqualTo(key);
            assertThat(fixture.handler.beginRejection(fixture.current(),
                    DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED).rejection())
                    .get().extracting(DocumentRejection::source).isEqualTo(sealed.processingPath());
        } else {
            assertThatThrownBy(() -> fixture.handler.finish(fixture.id))
                    .hasMessage("Document snapshot evidence is inconsistent");
            assertThat(fixture.current().terminalOutcome()).isEmpty();
            assertThat(sealed.processingPath()).exists();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "claim-config", "intent-target", "intent-source", "foreign-raw", "replaced-seal",
            "missing-seal", "claimed-ledger", "foreign-destination", "sidecar-directory", "temp-directory"
    })
    void conflictingEvidenceIsPreservedForOperatorReconciliation(String conflict) throws Exception {
        var fixture = new RejectionFixture();
        var linked = fixture.handler.admit(fixture.inbox, fixture.id, fixture.clock.instant());
        var original = fixture.current();
        var source = linked.source().processingPath();
        var evidence = fixture.reader.read(source);
        Path target = fixture.lifecycle.rejections().target(fixture.lifecycle.observationToken(fixture.id));
        Files.createDirectories(target.getParent());
        if (conflict.equals("replaced-seal")) {
            Files.move(source, tempDir.resolve("saved-seal"));
            Files.writeString(source, "different");
            assertThatThrownBy(() -> fixture.handler.beginRejection(original,
                    DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED)).hasMessage("Document snapshot identity changed");
            assertThat(source).hasContent("different");
            assertThat(fixture.current().rejection()).isEmpty();
            return;
        }
        if (conflict.equals("missing-seal")) {
            Files.move(source, tempDir.resolve("saved-seal"));
            assertThatThrownBy(() -> fixture.handler.beginRejection(original,
                    DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED))
                    .hasMessage("Linked document's private snapshot is missing");
            assertThat(fixture.current().rejection()).isEmpty();
            return;
        }
        Path journalSource = conflict.equals("intent-source") ? fixture.inbox : source;
        Path journalTarget = conflict.equals("intent-target") ? tempDir.resolve("another-failed") : target;
        var intent = new DocumentRejection(journalSource, journalTarget, evidence,
                DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED);
        var rejecting = fixture.service.beginRejection(original, intent);
        Path sidecar = target.resolveSibling(target.getFileName() + ".error");
        var handler = fixture.handler;
        switch (conflict) {
            case "claim-config" -> handler = new OrderedDocumentAdmissionHandler(fixture.service,
                    new FileSystemSourceLifecycle(tempDir.resolve("other-processing"), tempDir.resolve("done"),
                            target.getParent()), fixture.reader, new FileSourceHasher(), fixture.ledger);
            case "foreign-raw" -> Files.writeString(original.claimPath(), "foreign");
            case "claimed-ledger" -> fixture.ledger.markClaimed(linked.source());
            case "foreign-destination" -> {
                Files.move(source, tempDir.resolve("saved-seal"));
                Files.writeString(target, "foreign");
            }
            case "sidecar-directory" -> Files.createDirectory(sidecar);
            case "temp-directory" -> Files.createDirectory(sidecar.resolveSibling(sidecar.getFileName() + ".tmp"));
            default -> { /* The persisted intent itself is inconsistent. */ }
        }
        var selected = handler;
        assertThatThrownBy(() -> selected.finishRejection(rejecting)).isInstanceOf(RuntimeException.class);
        assertThat(fixture.current().terminalOutcome()).isEmpty();
        assertThat(fixture.current().rejection()).contains(intent);
        if (conflict.equals("foreign-destination")) {
            assertThat(target).hasContent("foreign");
        } else if (conflict.endsWith("-directory")) {
            assertThat(target).hasContent("bad");
        } else {
            assertThat(source).hasContent("bad");
        }
    }

    @Test
    void failureRemovesOnlyProvenPrivateSealSiblingsAndPinsFilesAgainstRetention() throws Exception {
        var fixture = new RejectionFixture();
        var claimed = fixture.handler.claim(fixture.inbox, fixture.id, fixture.clock.instant());
        Path raw = claimed.claimPath();
        Path sealed = raw.resolveSibling(raw.getFileName() + ".sealed");
        Files.copy(raw, sealed, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        Files.setLastModifiedTime(sealed, Files.getLastModifiedTime(raw));
        Path partialSeal = Files.writeString(raw.resolveSibling(raw.getFileName() + ".sealing"), "partial");
        var rejecting = fixture.handler.beginRejection(claimed, DocumentRejection.Code.SOURCE_HASH_FAILED);
        var target = rejecting.rejection().orElseThrow().target();
        Files.createDirectories(target.getParent());
        Files.move(sealed, target);
        Path sidecarTemp = Files.writeString(target.resolveSibling(target.getFileName() + ".error.tmp"), "partial");
        var store = new FileSystemRetentionStore(fixture.handler::pinsRejectionLeaf);
        var targetEntry = new RetentionEntry(target, Instant.EPOCH, target.getParent());
        store.delete(targetEntry);
        store.archive(targetEntry, tempDir.resolve("archive"));
        assertThat(target).hasContent("bad");
        assertThat(sidecarTemp).hasContent("partial");
        assertThat(store.list(target.getParent())).isEmpty();

        fixture.handler.finishRejection(rejecting);

        assertThat(raw).doesNotExist();
        assertThat(partialSeal).doesNotExist();
        assertThat(sidecarTemp).doesNotExist();
        assertThat(target).hasContent("bad");
        assertThat(fixture.current().sourceKey()).isEmpty();
        assertThat(fixture.handler.pinsRejectionLeaf(target)).isFalse();
        assertThat(fixture.handler.pinsRejectionLeaf(raw)).isFalse();
        assertThat(fixture.handler.pinsRejectionLeaf(target.resolveSibling("*__rejected"))).isFalse();
        assertThat(fixture.handler.pinsRejectionLeaf(target.resolveSibling("__rejected"))).isFalse();
    }

    @Test
    void fileJournalProtectsAnActiveCandidateAndUsesVersionForRetention() throws Exception {
        var fixture = new RejectionFixture();
        var evidence = fixture.reader.read(fixture.inbox);
        var reservation = new DocumentAdmissionReservation(fixture.id, fixture.inbox, evidence,
                fixture.lifecycle.prehashClaimPath(fixture.inbox, fixture.id), fixture.clock.instant());
        var reserved = fixture.journal.reserve(reservation);
        assertThat(fixture.journal.reserve(reservation)).isEqualTo(reserved);
        assertThatThrownBy(() -> fixture.journal.reserve(new DocumentAdmissionReservation(
                fixture.id, fixture.inbox, evidence, tempDir.resolve("different-claim"), fixture.clock.instant())))
                .hasMessage("Document occurrence reservation changed on retry");
        var otherId = new ObservationId("replacement-delivery");
        assertThatThrownBy(() -> fixture.journal.reserve(new DocumentAdmissionReservation(
                otherId, fixture.inbox, evidence, reservation.claimPath(), fixture.clock.instant())))
                .hasMessage("Document candidate already has an active reservation");
        assertThat(fixture.journal.purgeTerminal(otherId, 0)).isFalse();
        assertThat(fixture.journal.purgeTerminal(fixture.id, reserved.version())).isFalse();

        fixture.handler.finish(fixture.id);
        var linked = fixture.current();
        var rejecting = fixture.handler.beginRejection(linked, DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED);
        var terminal = rejecting.terminal(DocumentTerminalOutcome.REJECTED, fixture.clock.instant());
        assertThat(fixture.journal.replace(rejecting, terminal)).isTrue();
        assertThat(fixture.journal.purgeTerminal(fixture.id, terminal.version())).isFalse();
        assertThat(fixture.journal.replace(rejecting, terminal)).isFalse();

        fixture.service.complete(fixture.id, DocumentTerminalOutcome.REJECTED);
        var finalized = fixture.current();
        assertThat(fixture.journal.purgeTerminal(fixture.id, terminal.version())).isFalse();
        assertThat(fixture.journal.purgeTerminal(fixture.id, finalized.version())).isTrue();
        assertThat(fixture.journal.find(fixture.id)).isEmpty();
    }

    private final class RejectionFixture {
        private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T13:00:00Z"), ZoneOffset.UTC);
        private final ObservationId id = new ObservationId("fixture-delivery");
        private final FileSystemSourceLifecycle lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"), tempDir.resolve("done"), tempDir.resolve("failed"));
        private final FileDocumentCandidateEvidenceReader reader = new FileDocumentCandidateEvidenceReader();
        private final FileDocumentAdmissionJournal journal = new FileDocumentAdmissionJournal(tempDir.resolve("admissions"));
        private final FileIngestionLedger ledger = new FileIngestionLedger(tempDir.resolve("ledger"), clock);
        private final DocumentAdmissionService service = new DocumentAdmissionService(
                journal, new MemoryRegistrationStore(), clock);
        private final OrderedDocumentAdmissionHandler handler = new OrderedDocumentAdmissionHandler(
                service, lifecycle, reader, new FileSourceHasher(), ledger);
        private final Path inbox;

        private RejectionFixture() throws Exception {
            inbox = Files.writeString(tempDir.resolve("input.html"), "bad");
        }

        private DocumentAdmission current() { return service.find(id).orElseThrow(); }
    }

    private OrderedDocumentAdmissionHandler handler(Path journalPath,
                                                    FileSystemSourceLifecycle lifecycle,
                                                    ObservationRegistrationStore registrations,
                                                    Clock clock) {
        return new OrderedDocumentAdmissionHandler(
                new DocumentAdmissionService(
                        new FileDocumentAdmissionJournal(journalPath), registrations, clock),
                lifecycle, new FileDocumentCandidateEvidenceReader(), new FileSourceHasher(),
                new FileIngestionLedger(tempDir.resolve("ingestion-ledger"), clock));
    }

    private static final class MemoryRegistrationStore implements ObservationRegistrationStore {
        private static final String NAMESPACE = "0123456789abcdef0123456789abcdef";
        private final Map<ObservationId, RegisteredObservation> values = new LinkedHashMap<>();
        private long nextOrder = 1;
        private boolean failTerminalMark;

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
            if (failTerminalMark) {
                throw new IllegalStateException("Synthetic registration finalization failure");
            }
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
        public IngestionLedgerTransition recordRejected(SourceUnit source, Path failedPath, String code) {
            throw new AssertionError("Read-only snapshot ledger");
        }

        @Override
        public List<IngestionRecord> findIncomplete() {
            throw new AssertionError("document admission recovery uses point lookup");
        }
    }
}
