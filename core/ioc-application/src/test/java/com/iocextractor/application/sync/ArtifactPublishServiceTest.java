package com.iocextractor.application.sync;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceDescriptor;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactPublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void reconcilesMissingPairsAndPublishesOneSliceToEveryTarget() throws Exception {
        CompletedSlice slice = slice("reputation", "slice-one");
        FakeLedger ledger = new FakeLedger();
        FakeTransport transport = new FakeTransport();

        var result = service(catalog(slice), ledger, transport, targets("reputation"), diagnostics())
                .publish(new ArtifactPublishCommand(Optional.empty(), false));

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(ledger.records()).hasSize(2)
                .allSatisfy(record -> assertThat(record.status()).isEqualTo(PublishStatus.SUCCEEDED));
        assertThat(transport.published).containsExactlyInAnyOrder(
                "endpoint-a:/remote/a/slice-one",
                "endpoint-b:/remote/b/slice-one");
    }

    @Test
    void failureOfOneTargetDoesNotBlockAnotherTarget() throws Exception {
        CompletedSlice slice = slice("reputation", "slice-one");
        FakeLedger ledger = new FakeLedger();
        FakeTransport transport = new FakeTransport();
        transport.failPublishForEndpoint = "endpoint-a";

        var result = service(catalog(slice), ledger, transport, targets("reputation"), diagnostics())
                .publish(new ArtifactPublishCommand(Optional.of("reputation"), false));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(ledger.find("slice-one", "target-a")).hasValueSatisfying(record ->
                assertThat(record.status()).isEqualTo(PublishStatus.FAILED));
        assertThat(ledger.find("slice-one", "target-b")).hasValueSatisfying(record ->
                assertThat(record.status()).isEqualTo(PublishStatus.SUCCEEDED));
    }

    @Test
    void crashAfterRemoteCommitBeforeLedgerCommitRecoversToSucceeded() throws Exception {
        CompletedSlice slice = slice("reputation", "slice-one");
        FakeLedger ledger = new FakeLedger();
        FakeTransport transport = new FakeTransport();
        transport.remoteMarkers.put("endpoint-a:/remote/a/slice-one/_SUCCESS", HASH);

        var result = service(catalog(slice), ledger, transport, List.of(targetA("reputation")), diagnostics())
                .publish(new ArtifactPublishCommand(Optional.empty(), false));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(transport.published).isEmpty();
        assertThat(ledger.find("slice-one", "target-a")).hasValueSatisfying(record -> {
            assertThat(record.status()).isEqualTo(PublishStatus.SUCCEEDED);
            assertThat(record.remoteVerification()).contains("remote marker matched");
        });
    }

    @Test
    void remoteMarkerMismatchEmitsDiagnosticAndDoesNotMarkSuccess() throws Exception {
        CompletedSlice slice = slice("reputation", "slice-one");
        FakeLedger ledger = new FakeLedger();
        FakeTransport transport = new FakeTransport();
        CollectingDiagnosticSink diagnostics = diagnostics();
        transport.remoteMarkers.put("endpoint-a:/remote/a/slice-one/_SUCCESS", "b".repeat(64));

        var result = service(catalog(slice), ledger, transport, List.of(targetA("reputation")), diagnostics)
                .publish(new ArtifactPublishCommand(Optional.empty(), false));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(ledger.find("slice-one", "target-a")).hasValueSatisfying(record ->
                assertThat(record.status()).isEqualTo(PublishStatus.FAILED));
        assertThat(diagnostics.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(SyncDiagnosticCodes.PUBLISH_VERIFY_FAILED);
                    assertThat(diagnostic.context()).containsEntry("sliceId", "slice-one")
                            .containsEntry("targetId", "target-a");
                });
    }

    @Test
    void retentionGuardBlocksMissingAndNonTerminalPairs() {
        FakeLedger ledger = new FakeLedger();
        var guard = new PublishLedgerSliceRetentionGuard(ledger, targets("reputation"));
        SliceDescriptor descriptor = new SliceDescriptor("slice-one", "reputation", "slice-one", NOW);

        assertThat(guard.canDelete(descriptor)).isFalse();
        ledger.ensurePending(pending("slice-one", targetA("reputation")));
        ledger.ensurePending(pending("slice-one", targetB("reputation")));
        assertThat(guard.canDelete(descriptor)).isFalse();
        ledger.forceStatus("slice-one", "target-a", PublishStatus.SUCCEEDED);
        ledger.forceStatus("slice-one", "target-b", PublishStatus.FAILED);
        assertThat(guard.canDelete(descriptor)).isFalse();
        ledger.forceStatus("slice-one", "target-b", PublishStatus.ABANDONED);
        assertThat(guard.canDelete(descriptor)).isTrue();
    }

    private ArtifactPublishService service(CompletedSliceCatalog catalog,
                                           FakeLedger ledger,
                                           FakeTransport transport,
                                           List<PublishTarget> targets,
                                           CollectingDiagnosticSink diagnostics) {
        return new ArtifactPublishService(
                catalog,
                ledger,
                transport,
                targets,
                new Retrier(new RetryPolicy(1, java.time.Duration.ofMillis(1), 1.0d,
                        java.time.Duration.ofMillis(1), false), ignored -> { }),
                diagnostics,
                CLOCK);
    }

    private CompletedSliceCatalog catalog(CompletedSlice... slices) {
        Map<String, List<CompletedSlice>> byProfile = new LinkedHashMap<>();
        for (CompletedSlice slice : slices) {
            byProfile.computeIfAbsent(slice.profile(), ignored -> new ArrayList<>()).add(slice);
        }
        return profile -> byProfile.getOrDefault(profile, List.of());
    }

    private CollectingDiagnosticSink diagnostics() {
        return new CollectingDiagnosticSink();
    }

    private List<PublishTarget> targets(String profile) {
        return List.of(targetA(profile), targetB(profile));
    }

    private PublishTarget targetA(String profile) {
        return new PublishTarget("target-a", "endpoint-a", "/remote/a", profile);
    }

    private PublishTarget targetB(String profile) {
        return new PublishTarget("target-b", "endpoint-b", "/remote/b", profile);
    }

    private CompletedSlice slice(String profile, String sliceId) throws Exception {
        Path directory = tempDir.resolve(profile).resolve(sliceId);
        Files.createDirectories(directory);
        SliceManifest manifest = new SliceManifest(
                1,
                sliceId,
                sliceId,
                profile,
                NOW,
                ExportMode.COMPLETE,
                HASH,
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new SliceArtifactManifest(
                        "masks",
                        "masks.csv",
                        1,
                        ArtifactCoverage.empty(),
                        1,
                        HASH,
                        HASH,
                        HASH)));
        return new CompletedSlice(sliceId, profile, sliceId, HASH, directory, manifest);
    }

    private PublishRecord pending(String sliceId, PublishTarget target) {
        return PublishRecord.pending(
                sliceId,
                target.targetId(),
                target.exportProfile(),
                sliceId,
                HASH,
                target.endpoint(),
                target.sliceRemotePath(sliceId),
                NOW);
    }

    private static final class FakeTransport implements FileTransport {
        private final List<String> published = new ArrayList<>();
        private final Map<String, String> remoteMarkers = new LinkedHashMap<>();
        private String failPublishForEndpoint;

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            return List.of();
        }

        @Override
        public Optional<RemoteObject> stat(String endpoint, String remotePath) {
            if (remoteMarkers.containsKey(endpoint + ":" + remotePath)) {
                return Optional.of(new RemoteObject(remotePath, remoteMarkers.get(endpoint + ":" + remotePath).length(), NOW));
            }
            return Optional.empty();
        }

        @Override
        public void get(String endpoint, String remotePath, Path localDestination) {
            String marker = remoteMarkers.get(endpoint + ":" + remotePath);
            if (marker == null) {
                throw new RemoteTransportException(RemoteErrorKind.NOT_FOUND, "missing marker");
            }
            try {
                Files.writeString(localDestination, marker, StandardCharsets.US_ASCII);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void delete(String endpoint, String remotePath) {
            throw new UnsupportedOperationException("delete is not used by publish service");
        }

        @Override
        public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
            if (request.endpoint().equals(failPublishForEndpoint)) {
                throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "publish failed");
            }
            published.add(request.endpoint() + ":" + request.remotePath());
            return new PublishReceipt(request.remotePath(), "published " + request.remotePath());
        }
    }

    private static final class FakeLedger implements PublishLedger {
        private final Map<String, PublishRecord> records = new LinkedHashMap<>();

        @Override
        public PublishRecord ensurePending(PublishRecord pending) {
            records.putIfAbsent(key(pending.sliceId(), pending.targetId()), pending);
            return records.get(key(pending.sliceId(), pending.targetId()));
        }

        @Override
        public Optional<PublishRecord> find(String sliceId, String targetId) {
            return Optional.ofNullable(records.get(key(sliceId, targetId)));
        }

        @Override
        public List<PublishRecord> findBySlice(String sliceId) {
            return records.values().stream()
                    .filter(record -> record.sliceId().equals(sliceId))
                    .toList();
        }

        @Override
        public List<PublishRecord> findRetryable() {
            return records.values().stream()
                    .filter(record -> record.status() == PublishStatus.PENDING
                            || record.status() == PublishStatus.FAILED)
                    .toList();
        }

        @Override
        public PublishRecord transition(String sliceId,
                                        String targetId,
                                        PublishStatus expected,
                                        PublishStatus next,
                                        String lastError,
                                        String remoteVerification) {
            PublishRecord current = find(sliceId, targetId).orElseThrow();
            if (current.status() != expected) {
                throw new IllegalStateException("conflict");
            }
            PublishRecord updated = new PublishRecord(
                    current.sliceId(),
                    current.targetId(),
                    current.profile(),
                    current.sliceName(),
                    current.manifestSha256(),
                    current.endpoint(),
                    current.remotePath(),
                    next,
                    next == PublishStatus.IN_PROGRESS || next == PublishStatus.FAILED
                            ? current.attempts() + 1 : current.attempts(),
                    lastError,
                    remoteVerification == null ? current.remoteVerification() : remoteVerification,
                    current.createdAt(),
                    NOW);
            records.put(key(sliceId, targetId), updated);
            return updated;
        }

        private List<PublishRecord> records() {
            return List.copyOf(records.values());
        }

        private void forceStatus(String sliceId, String targetId, PublishStatus status) {
            PublishRecord current = find(sliceId, targetId).orElseThrow();
            records.put(key(sliceId, targetId), new PublishRecord(
                    current.sliceId(), current.targetId(), current.profile(), current.sliceName(),
                    current.manifestSha256(), current.endpoint(), current.remotePath(), status,
                    current.attempts(), current.lastError(), current.remoteVerification(),
                    current.createdAt(), NOW));
        }

        private String key(String sliceId, String targetId) {
            return sliceId + "\n" + targetId;
        }
    }
}
