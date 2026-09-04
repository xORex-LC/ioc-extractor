package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.PurgeImportTerminalSourceCommand;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class LocalManagedImportSourceLifecycleIT {

    private static final ImportSourceId SOURCE_ID = new ImportSourceId("local-feed");
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void fullScanWaitsForStabilityAndProducesAnExactPrivateSnapshot() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        Path source = Files.writeString(fixture.inbox().resolve("arbitrary name.csv"), "id;ip\n7;192.0.2.7\n");

        assertThat(fixture.lifecycle().detect(SOURCE_ID, NOW)).isEmpty();
        List<ImportSourceCandidate> stable = fixture.lifecycle().detect(SOURCE_ID, NOW.plusSeconds(5));

        assertThat(stable).hasSize(1);
        var result = fixture.lifecycle().claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("delivery-7"), SOURCE_ID, stable.getFirst().candidateToken()));
        Path snapshot = fixture.lifecycle().resolveSnapshot(result.snapshot().reference());
        assertThat(source).doesNotExist();
        assertThat(snapshot).hasContent("id;ip\n7;192.0.2.7\n");
        assertThat(result.snapshot().size()).isEqualTo(Files.size(snapshot));
        assertThat(result.snapshot().digest().value()).isEqualTo(sha256(Files.readAllBytes(snapshot)));
        assertThat(Files.isWritable(snapshot)).isFalse();
    }

    @Test
    void duplicateOrLostWatchSignalIsRecoveredByTheSameFullListingToken() throws Exception {
        Fixture fixture = fixture(Duration.ZERO);
        Files.writeString(fixture.inbox().resolve("feed.csv"), "ip\n192.0.2.1\n");

        ImportSourceCandidate first = fixture.lifecycle().detect(SOURCE_ID, NOW).getFirst();
        ImportSourceCandidate replay = fixture.lifecycle().detect(SOURCE_ID, NOW.plusSeconds(60)).getFirst();

        assertThat(replay.candidateToken()).isEqualTo(first.candidateToken());
    }

    @Test
    void symlinkAndPathEscapeNeverReachClaimedBytes() throws Exception {
        Fixture fixture = fixture(Duration.ZERO);
        Path outside = Files.writeString(tempDir.resolve("outside.csv"), "secret");
        Files.createSymbolicLink(fixture.inbox().resolve("link.csv"), outside);

        assertThat(fixture.lifecycle().detect(SOURCE_ID, NOW)).isEmpty();

        String escape = "v1." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("../outside.csv".getBytes(StandardCharsets.UTF_8))
                + ".6.1." + "a".repeat(64);
        assertThatThrownBy(() -> fixture.lifecycle().claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("escape"), SOURCE_ID, escape)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outside).hasContent("secret");
    }

    @Test
    void existingOwnershipTargetIsNeverReplaced() throws Exception {
        Fixture fixture = fixture(Duration.ZERO);
        Files.writeString(fixture.inbox().resolve("feed.csv"), "new");
        ImportSourceCandidate candidate = fixture.lifecycle().detect(SOURCE_ID, NOW).getFirst();
        Path collision = fixture.processing().resolve(sha256("delivery-collision".getBytes(StandardCharsets.UTF_8)))
                .resolve("source");
        Files.createDirectories(collision.getParent());
        Files.writeString(collision, "existing");

        assertThatThrownBy(() -> fixture.lifecycle().claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("delivery-collision"), SOURCE_ID, candidate.candidateToken())))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("target already exists");
        assertThat(collision).hasContent("existing");
    }

    @Test
    void unsupportedAtomicOwnershipHasNoCopyFallback() throws Exception {
        Fixture paths = fixture(Duration.ZERO);
        Files.writeString(paths.inbox().resolve("feed.csv"), "bytes");
        var ownership = new StrictAtomicFileOwnership((source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        });
        var lifecycle = lifecycle(paths, ownership, LocalManagedImportSourceLifecycleIT::copyEvidence);
        ImportSourceCandidate candidate = lifecycle.detect(SOURCE_ID, NOW).getFirst();

        assertThatThrownBy(() -> lifecycle.claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("unsupported"), SOURCE_ID, candidate.candidateToken())))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("not supported");
        assertThat(paths.inbox().resolve("feed.csv")).exists();
    }

    @Test
    void producerMutationAndDiskFullLeaveNoPublishedSnapshot() throws Exception {
        Fixture paths = fixture(Duration.ZERO);
        Files.writeString(paths.inbox().resolve("mutable.csv"), "abc");
        var mutating = lifecycle(paths, new StrictAtomicFileOwnership(), (source, target, limit) -> {
            var evidence = copyEvidence(source, target, limit);
            Files.writeString(source, "abcd");
            return evidence;
        });
        ImportSourceCandidate mutable = mutating.detect(SOURCE_ID, NOW).getFirst();
        assertThatThrownBy(() -> mutating.claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("mutating"), SOURCE_ID, mutable.candidateToken())))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("changed while snapshotting");

        Files.writeString(paths.inbox().resolve("disk-full.csv"), "content");
        var full = lifecycle(paths, new StrictAtomicFileOwnership(), (source, target, limit) -> {
            throw new IOException("No space left on device");
        });
        ImportSourceCandidate diskFull = full.detect(SOURCE_ID, NOW).stream()
                .filter(item -> item.candidateToken().contains(encoded("disk-full.csv")))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> full.claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("disk-full"), SOURCE_ID, diskFull.candidateToken())))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("materialize");
        assertThat(paths.snapshots().resolve(sha256("disk-full".getBytes(StandardCharsets.UTF_8)))
                .resolve("snapshot.csv")).doesNotExist();
    }

    @Test
    void claimReplayUsesOwnedSourceWhileChangedAndOversizedCandidatesFailClosed() throws Exception {
        Fixture replayPaths = fixture(Duration.ZERO);
        Files.writeString(replayPaths.inbox().resolve("replay.csv"), "stable");
        ImportSourceCandidate replayCandidate = replayPaths.lifecycle().detect(SOURCE_ID, NOW).getFirst();
        ClaimImportSourceCommand replayCommand = new ClaimImportSourceCommand(
                new ImportDeliveryId("delivery-replay"), SOURCE_ID, replayCandidate.candidateToken());

        var first = replayPaths.lifecycle().claim(replayCommand);
        var replayed = replayPaths.lifecycle().claim(replayCommand);

        assertThat(replayed).isEqualTo(first);
        assertThat(replayPaths.inbox().resolve("replay.csv")).doesNotExist();

        Fixture changedPaths = fixture(Duration.ZERO);
        Path changed = Files.writeString(changedPaths.inbox().resolve("changed.csv"), "old");
        ImportSourceCandidate changedCandidate = changedPaths.lifecycle().detect(SOURCE_ID, NOW).getFirst();
        Files.writeString(changed, "new-and-longer");
        assertThatThrownBy(() -> changedPaths.lifecycle().claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("delivery-changed"), SOURCE_ID, changedCandidate.candidateToken())))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import candidate changed after detection");

        Fixture oversizedPaths = fixture(Duration.ZERO, 3);
        Files.writeString(oversizedPaths.inbox().resolve("oversized.csv"), "four");
        ImportSourceCandidate oversized = oversizedPaths.lifecycle().detect(SOURCE_ID, NOW).getFirst();
        assertThatThrownBy(() -> oversizedPaths.lifecycle().claim(new ClaimImportSourceCommand(
                new ImportDeliveryId("delivery-oversized"), SOURCE_ID, oversized.candidateToken())))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Import snapshot exceeds configured byte limit");
    }

    @Test
    void dispositionReleasesClaimOnlyAfterCompleteTerminalUnitIsPublished() throws Exception {
        for (ImportTerminalOutcome outcome : List.of(
                ImportTerminalOutcome.SUCCEEDED, ImportTerminalOutcome.REJECTED)) {
            Fixture fixture = fixture(Duration.ZERO);
            Files.writeString(fixture.inbox().resolve(outcome.name() + ".csv"), "bytes");
            ImportSourceCandidate candidate = fixture.lifecycle().detect(SOURCE_ID, NOW).getFirst();
            ImportDeliveryId deliveryId = new ImportDeliveryId("delivery-" + outcome.name().toLowerCase());
            fixture.lifecycle().claim(new ClaimImportSourceCommand(
                    deliveryId, SOURCE_ID, candidate.candidateToken()));
            DispositionImportSourceCommand command = new DispositionImportSourceCommand(
                    deliveryId, SOURCE_ID, outcome);
            Path outcomeRoot = outcome == ImportTerminalOutcome.REJECTED
                    ? fixture.quarantine() : fixture.terminal();
            Path unit = outcomeRoot.resolve(sha256(deliveryId.value().getBytes(StandardCharsets.UTF_8)));

            assertThatThrownBy(() -> fixture.lifecycle().disposition(command))
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessage("Protected import terminal unit is not published");
            Files.createDirectories(unit);
            Files.writeString(unit.resolve("source.csv"), "bytes");
            assertThatThrownBy(() -> fixture.lifecycle().disposition(command))
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessage("Protected import terminal unit is not published");
            Files.writeString(unit.resolve("report.json"), "{}");

            fixture.lifecycle().disposition(command);
            fixture.lifecycle().disposition(command);

            assertThat(fixture.processing()
                    .resolve(sha256(deliveryId.value().getBytes(StandardCharsets.UTF_8))))
                    .doesNotExist();
        }
    }

    @Test
    void capabilityAndPurgeValidateTheConfiguredSourceWithoutTouchingTerminalBytes() throws Exception {
        Fixture fixture = fixture(Duration.ZERO);

        assertThat(fixture.lifecycle().probe(SOURCE_ID).sourceId()).isEqualTo(SOURCE_ID);
        fixture.lifecycle().purge(new PurgeImportTerminalSourceCommand(
                new ImportDeliveryId("delivery-purge"), SOURCE_ID,
                ImportManagedObjectId.from(new ImportDeliveryId("delivery-purge")),
                ImportTerminalOutcome.REJECTED));
        assertThatThrownBy(() -> fixture.lifecycle().probe(new ImportSourceId("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown local import source: unknown");
    }

    @Test
    void malformedCandidateTokensAreRejectedBeforeAnyOwnershipChange() throws Exception {
        Fixture fixture = fixture(Duration.ZERO);
        String hash = "a".repeat(64);
        List<String> malformed = List.of(
                "v1.too.short",
                "v2." + encoded("feed.csv") + ".1.1." + hash,
                "v1." + encoded("feed.csv") + ".1.1.short",
                "v1.*.1.1." + hash,
                "v1." + encoded("feed.csv") + ".-1.1." + hash,
                "v1." + encoded("feed.csv") + ".1.-1." + hash);

        for (String token : malformed) {
            assertThatThrownBy(() -> fixture.lifecycle().claim(new ClaimImportSourceCommand(
                    new ImportDeliveryId("delivery-malformed"), SOURCE_ID, token)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Malformed local import candidate token");
        }
        try (var processing = Files.list(fixture.processing())) {
            assertThat(processing).isEmpty();
        }
    }

    @Test
    void constructorRejectsInvalidAndOverlappingTrustRoots() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("constructor"));
        Path inbox = root.resolve("inbox");
        Path processing = root.resolve("processing");
        Path snapshots = root.resolve("snapshots");
        Path terminal = root.resolve("terminal");
        Path quarantine = root.resolve("quarantine");
        LocalImportSourceDefinition source = new LocalImportSourceDefinition(SOURCE_ID, inbox);

        assertThatThrownBy(() -> new LocalManagedImportSourceLifecycle(
                List.of(), processing, snapshots, terminal, quarantine, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At least one local import source is required");
        assertThatThrownBy(() -> new LocalManagedImportSourceLifecycle(
                List.of(source), processing, snapshots, terminal, quarantine, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Import quiet period must not be negative");
        assertThatThrownBy(() -> new LocalManagedImportSourceLifecycle(
                List.of(source), processing, snapshots, terminal, quarantine, Duration.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum import snapshot bytes must be positive");
        assertThatThrownBy(() -> new LocalManagedImportSourceLifecycle(
                List.of(source, new LocalImportSourceDefinition(SOURCE_ID, root.resolve("second-inbox"))),
                processing, snapshots, terminal, quarantine, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate local import source ID: local-feed");
        assertThatThrownBy(() -> new LocalManagedImportSourceLifecycle(
                List.of(source), processing, processing, terminal, quarantine, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Managed import paths must not overlap");

        Path real = Files.createDirectories(root.resolve("real-processing"));
        Path link = root.resolve("linked-processing");
        Files.createSymbolicLink(link, real);
        assertThatThrownBy(() -> new LocalManagedImportSourceLifecycle(
                List.of(source), link, snapshots, terminal, quarantine, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("processingRoot must not be a symbolic link");
    }

    private Fixture fixture(Duration quietPeriod) throws IOException {
        return fixture(quietPeriod, 1024 * 1024);
    }

    private Fixture fixture(Duration quietPeriod, long maximumSnapshotBytes) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("fixture-" + java.util.UUID.randomUUID()));
        Path inbox = root.resolve("inbox");
        Path processing = root.resolve("processing");
        Path snapshots = root.resolve("snapshots");
        Path terminal = root.resolve("terminal");
        Path quarantine = root.resolve("quarantine");
        var lifecycle = new LocalManagedImportSourceLifecycle(
                List.of(new LocalImportSourceDefinition(SOURCE_ID, inbox)), processing, snapshots,
                terminal, quarantine, quietPeriod, maximumSnapshotBytes);
        return new Fixture(lifecycle, inbox, processing, snapshots, terminal, quarantine);
    }

    private LocalManagedImportSourceLifecycle lifecycle(
            Fixture paths,
            StrictAtomicFileOwnership ownership,
            LocalManagedImportSourceLifecycle.SnapshotCopier copier) {
        return new LocalManagedImportSourceLifecycle(
                List.of(new LocalImportSourceDefinition(SOURCE_ID, paths.inbox())),
                paths.processing(), paths.snapshots(), paths.terminal(), paths.quarantine(),
                Duration.ZERO, 1024 * 1024, ownership, copier);
    }

    private static long copyEvidence(
            Path source, Path target, long limit) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        if (bytes.length > limit) {
            throw new IOException("limit");
        }
        Files.write(target, bytes);
        return bytes.length;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encoded(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(
            LocalManagedImportSourceLifecycle lifecycle,
            Path inbox,
            Path processing,
            Path snapshots,
            Path terminal,
            Path quarantine) {
    }
}
