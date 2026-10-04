package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises real disk reduction, sealed recovery and resource ownership with deliberately tiny limits. */
@IntegrationTest
@Timeout(20)
class JdbcDocumentPreparationWorkspaceIT {
    private static final List<String> HEADER = List.of("id", "value", "name", "source");
    private static final Map<String, ArtifactWritePolicy> POLICIES = Map.of("first", ArtifactWritePolicy.keepFirst(),
            "last", new ArtifactWritePolicy(ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, "name", Map.of()));
    @TempDir Path temporary;

    @Test
    void rowCodecRejectsMalformedVersionTrailingBytesAndInvalidUtf8() throws Exception {
        var codec = new DocumentRowCodec(4096, 2048);
        byte[] valid = codec.encode(row("key", "name", "source", 1));
        assertThat(codec.decode(valid)).isEqualTo(row("key", "name", "source", 1));
        byte[] version = valid.clone();
        version[3] = 2;
        assertThatThrownBy(() -> codec.decode(version)).hasMessageContaining("Corrupt document");
        assertThatThrownBy(() -> codec.decode(java.util.Arrays.copyOf(valid, valid.length + 1)))
                .hasMessageContaining("Corrupt document");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var data = new java.io.DataOutputStream(bytes)) {
            data.writeInt(1); data.writeInt(-1); data.writeInt(1);
            data.writeInt(1); data.writeByte('x');
            data.writeInt(1); data.writeByte(0x80); data.writeInt(0);
        }
        assertThatThrownBy(() -> codec.decode(bytes.toByteArray())).hasMessageContaining("Corrupt document");
    }

    @Test
    void pinCopyRejectsGrowingInputBeforeWritingBeyondByteLimit() throws Exception {
        Path input = temporary.resolve("large-source");
        Files.write(input, new byte[20000]);
        Path target = temporary.resolve("snapshot");
        assertThatThrownBy(() -> DocumentWorkspaceFiles.copy(input, target, 17000))
                .hasMessageContaining("exceeds quota");
        assertThat(Files.size(target)).isLessThanOrEqualTo(17000);
    }

    @Test
    void nonemptyUnownedRootIsNeverUsedOrDeleted() throws Exception {
        Path root = temporary.resolve("workspace");
        Files.createDirectories(root);
        Path unrelated = root.resolve("operator-data");
        Files.writeString(unrelated, "preserve");
        assertThatThrownBy(() -> factory(limits(1)).open(command("unowned"), POLICIES))
                .hasRootCauseMessage("Document workspace root must be empty or owned");
        assertThat(Files.readString(unrelated)).isEqualTo("preserve");
        assertThat(root.resolve(".owner")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 4096})
    void diskReducerMatchesIndependentWholeRowOracleAcrossManyBatchBoundaries(int cacheKiB) throws Exception {
        var expected = new LinkedHashMap<String, PreparedArtifactRow>();
        var first = new LinkedHashMap<String, PreparedArtifactRow>();
        var random = new Random(4204);
        var command = command("selection");
        var limits = new DocumentPreparationLimits(16 * 1024 * 1024, cacheKiB, 4096, 2048,
                10485760, 104857600, 2);
        try (var workspace = factory(limits).open(command, POLICIES)) {
            for (int index = 0; index < 1000; index++) {
                String key = "host-" + random.nextInt(100);
                String name = index % 5 == 0 ? "" : "name-" + index;
                var row = row(key, name, "source-" + index, index);
                first.putIfAbsent(key, row);
                if (!expected.containsKey(key) || !name.isBlank()) { expected.put(key, row); }
                workspace.append(new RoutedArtifactCandidate("first", row), true, false);
                workspace.append(new RoutedArtifactCandidate("last", row), true, false);
            }
            var plans = workspace.seal(descriptors(), summary(1000));
            assertThat(plans.get(0).rows().snapshot()).containsExactlyElementsOf(first.values());
            assertThat(plans.get(1).rows().snapshot()).containsExactlyElementsOf(expected.values());
            // A new cursor repeats the same frozen row/position evidence.
            assertThat(plans.get(1).rows().snapshot()).containsExactlyElementsOf(expected.values());
            workspace.discard();
        }
        assertThat(pin(command)).doesNotExist();
    }

    @Test
    void invalidPhysicalBudgetsCannotOpenAnUnboundedWorkspace() {
        long memory = 2097152;
        for (var invalid : List.of(
                new long[]{memory, 63, 4096, 2048, 10485760, 104857600, 128},
                new long[]{memory, 64, 4096, 0, 10485760, 104857600, 128},
                new long[]{memory, 64, 4096, 4097, 10485760, 104857600, 128},
                new long[]{memory, 64, 16777217, 2048, 10485760, 104857600, 128},
                new long[]{memory, 64, 4096, 2048, 10485760, 104857600, 0},
                new long[]{memory, 64, 4096, 2048, 10485760, 104857600, 4097},
                new long[]{memory, 64, 4096, 2048, 65535, 104857600, 128},
                new long[]{memory, 64, 4096, 2048, 10485760, 10485759, 128},
                new long[]{163839, 64, 4096, 2048, 10485760, 104857600, 128})) {
            assertThatThrownBy(() -> new DocumentPreparationLimits(invalid[0], (int) invalid[1],
                    (int) invalid[2], (int) invalid[3], invalid[4], invalid[5], (int) invalid[6]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid document preparation limits");
        }
        assertThat(temporary.resolve("workspace")).doesNotExist();
    }

    @Test
    void hashCollisionCannotMergeDifferentFullIdentityMaterial() throws Exception {
        ArtifactIdentityResolver collision = new ArtifactIdentityResolver() {
            public Optional<ArtifactRowKey> keyOf(String artifact, ArtifactRow row) { return Optional.of(new ArtifactRowKey("same-digest")); }
            public Optional<CanonicalKeyMaterial> materialOf(String artifact, ArtifactRow row) {
                return Optional.of(new CanonicalKeyMaterial("definition", "a".repeat(64), row.value("value")));
            }
        };
        var factory = new JdbcDocumentPreparationWorkspaceFactory(temporary.resolve("workspace"), limits(1), collision, "policy");
        try (var workspace = factory.open(command("collision"), POLICIES)) {
            workspace.append(candidate("first", "a", "one"), true, false);
            workspace.append(candidate("first", "b", "two"), true, false);
            assertThat(workspace.seal(descriptors(), summary(2)).getFirst().rows().size()).isEqualTo(2);
            workspace.discard();
        }
    }

    @Test
    void compositeIdentityAndExplicitNullArePreserved() throws Exception {
        var resolver = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("first", List.of("value", "name"), false, 1),
                new ArtifactIdentityDefinition("last", List.of("value", "name"), false, 1)));
        var factory = new JdbcDocumentPreparationWorkspaceFactory(temporary.resolve("workspace"), limits(1), resolver, "policy");
        try (var workspace = factory.open(command("composite"), POLICIES)) {
            var nullable = new LinkedHashMap<String, String>();
            nullable.put("id", null); nullable.put("value", "host"); nullable.put("name", null);
            var nullRow = new PreparedArtifactRow(ArtifactRow.ordered(nullable), Optional.of("id"));
            workspace.append(new RoutedArtifactCandidate("first", nullRow), true, false);
            workspace.append(candidate("first", "host", "different"), true, false);
            var rows = workspace.seal(descriptors(), summary(2)).getFirst().rows().snapshot();
            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst().template().values()).containsEntry("name", null).doesNotContainKey("source");
            workspace.discard();
        }
    }

    @Test
    void retainedObservationPolicyKeepsEligibleOccurrencesInsteadOfCollapsingFinalKeys() throws Exception {
        try (var workspace = factory(limits(1)).open(command("observations"), POLICIES)) {
            assertThat(workspace.firstOriginal("original-a")).isTrue();
            workspace.append(candidate("first", "host", "one"), true, true);
            assertThat(workspace.firstOriginal("original-a")).isFalse();
            workspace.append(candidate("first", "host", "dropped"), false, true);
            assertThat(workspace.firstOriginal("original-b")).isTrue();
            workspace.append(candidate("first", "host", "two"), true, true);
            assertThat(workspace.seal(descriptors(), summary(3)).getFirst().rows().snapshot())
                    .extracting(row -> row.template().value("name")).containsExactly("one", "two");
            workspace.discard();
        }
    }

    @Test
    void restartRevalidatesEveryCandidateIncludingLosersAndOriginalDuplicates() throws Exception {
        var command = command("restart");
        var factory = factory(limits(1));
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var workspace = factory.open(command, POLICIES)) {
                assertThat(workspace.firstOriginal("a")).isTrue();
                workspace.append(candidate("first", "same", "winner"), true, false);
                assertThat(workspace.firstOriginal("a")).isFalse();
                workspace.append(candidate("first", "same", "loser"), false, false);
                var rows = workspace.seal(descriptors(), summary(2)).getFirst().rows().snapshot();
                assertThat(rows).singleElement().satisfies(row -> assertThat(row.template().value("name")).isEqualTo("winner"));
                workspace.beginPromotion();
                assertThat(workspace.promotionStarted()).isTrue();
                if (attempt == 1) { workspace.discard(); }
            }
            if (attempt == 0) { assertThat(pin(command).resolve("seal")).exists(); }
        }
        assertThat(pin(command)).doesNotExist();
    }

    @Test
    void losingCandidateOrCheckpointCountMismatchFailsClosedOnRetry() throws Exception {
        var command = command("loser-mismatch");
        try (var workspace = factory(limits(1)).open(command, POLICIES)) {
            workspace.append(candidate("first", "same", "winner"), true, false);
            workspace.append(candidate("first", "same", "loser"), true, false);
            workspace.seal(descriptors(), summary(2));
        }
        try (var workspace = factory(limits(1)).open(command, POLICIES)) {
            workspace.append(candidate("first", "same", "winner"), true, false);
            assertThatThrownBy(() -> workspace.append(candidate("first", "same", "changed loser"), true, false))
                    .hasMessageContaining("candidate differs");
        }
        try (var workspace = factory(limits(1)).open(command, POLICIES)) {
            workspace.append(candidate("first", "same", "winner"), true, false);
            workspace.append(candidate("first", "same", "loser"), true, false);
            assertThatThrownBy(() -> workspace.seal(descriptors(), summary(3))).hasMessageContaining("schema mismatch");
            workspace.discard();
        }
    }

    @Test
    void corruptedDatabaseOrManifestCannotBePromoted() throws Exception {
        for (String file : List.of("prepared.db", "catalog", "identity", "seal")) {
            var command = command("corrupt-" + file);
            try (var workspace = factory(limits(1)).open(command, POLICIES)) { workspace.seal(descriptors(), summary(0)); }
            Files.writeString(pin(command).resolve(file), "corrupt");
            assertThatThrownBy(() -> factory(limits(1)).open(command, POLICIES))
                    .hasMessageContaining("Cannot open private document workspace");
            // Test-owned corrupted pins are explicitly disposed, not left on the developer disk.
            JdbcDocumentPreparationWorkspaceFactory.deletePrivateDirectory(pin(command));
        }
    }

    @Test
    void changedPolicySourceOrSchemaIsRejected() throws Exception {
        var command = command("identity");
        var factory = factory(limits(1));
        try (var workspace = factory.open(command, POLICIES)) { workspace.seal(descriptors(), summary(0)); }
        var changed = new JdbcDocumentPreparationWorkspaceFactory(temporary.resolve("workspace"), limits(1), identities(), "changed-policy");
        assertThatThrownBy(() -> changed.open(command, POLICIES)).hasRootCauseMessage("Document preparation pin identity mismatch");
        try (var workspace = factory.open(command, POLICIES)) {
            var descriptor = new ArtifactWritePlan("first", List.of("wrong"), List.of(), ids());
            assertThatThrownBy(() -> workspace.seal(List.of(descriptor), summary(0))).hasMessageContaining("schema mismatch");
        }
        Files.writeString(command.source(), "changed source");
        assertThatThrownBy(() -> factory.open(command, POLICIES)).hasRootCauseMessage("Document preparation pin identity mismatch");
    }

    @Test
    void unsealedFailureDeletesPrivateFilesAndReleasesAdmission() throws Exception {
        var command = command("failed");
        var factory = factory(limits(1));
        try (var workspace = factory.open(command, POLICIES)) {
            workspace.append(candidate("first", "a", "b"), true, false);
            assertThatThrownBy(workspace::beginPromotion).hasMessageContaining("unsealed");
        }
        assertThat(pin(command)).doesNotExist();
        try (var workspace = factory.open(command, POLICIES)) { workspace.discard(); }
    }

    @Test
    void rowAndDiskLimitsFailBeforeCanonicalPromotionAndCleanUp() throws Exception {
        var command = command("limits");
        try (var workspace = factory(limits(1)).open(command, POLICIES)) {
            assertThatThrownBy(() -> workspace.append(candidate("first", "a", "x".repeat(2049)), true, false))
                    .hasRootCauseMessage("Document field exceeds byte limit");
            assertThatThrownBy(() -> workspace.firstOriginal("a".repeat(2049))).hasMessageContaining("field limit");
        }
        var small = new DocumentPreparationLimits(1048576, 64, 4096, 2048, 131072, 131072, 1);
        try (var workspace = factory(small).open(command, POLICIES)) {
            assertThatThrownBy(() -> {
                for (int index = 0; index < 1000; index++) {
                    workspace.append(candidate("first", "value-" + index, "x".repeat(1000)), true, false);
                }
            }).hasMessageContaining("storage failed");
        }
        assertThat(pin(command)).doesNotExist();
    }

    @Test
    void snapshotAndAggregateDiskAdmissionAreBounded() throws Exception {
        var small = new DocumentPreparationLimits(1048576, 64, 4096, 2048, 131072, 131072, 1);
        var command = command("big-source");
        Files.writeString(command.source(), "a".repeat(32769));
        assertThatThrownBy(() -> factory(small).open(command, POLICIES)).hasRootCauseMessage("Document source snapshot exceeds workspace quota");
        assertThat(pin(command)).doesNotExist();
        var factory = factory(small);
        try (var workspace = factory.open(command("one-live"), POLICIES)) {
            assertThatThrownBy(() -> factory.open(command("second-live"), POLICIES)).hasRootCauseMessage("Document preparation disk admission exhausted");
            workspace.discard();
        }
    }

    @Test
    void cursorCloseShortCircuitAndWorkspaceCloseReleaseTheirOwnership() throws Exception {
        var workspace = factory(limits(1)).open(command("cursor"), POLICIES);
        workspace.append(candidate("first", "a", "one"), true, false);
        var rows = workspace.seal(descriptors(), summary(1)).getFirst().rows();
        assertThat(rows.anyMatch(row -> row.template().value("value").equals("a"))).isTrue();
        var cursor = rows.open();
        assertThat(cursor.next()).isTrue();
        assertThatThrownBy(rows::open).hasMessageContaining("one sealed cursor");
        workspace.discard(); workspace.close(); workspace.close();
        assertThatThrownBy(cursor::next).hasMessageContaining("closed");
        assertThatThrownBy(rows::open).hasMessageContaining("closed");
    }

    @Test
    void interruptionPropagatesAndLeavesNoLeasedResources() throws Exception {
        var factory = factory(limits(1));
        try (var workspace = factory.open(command("interrupted"), POLICIES)) {
            workspace.append(candidate("first", "a", "one"), true, false);
            var rows = workspace.seal(descriptors(), summary(1)).getFirst().rows();
            try (var cursor = rows.open()) {
                Thread.currentThread().interrupt();
                try { assertThatThrownBy(cursor::next).hasMessageContaining("interrupted"); }
                finally { Thread.interrupted(); }
            }
            workspace.discard();
        }
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> factory.open(command("interrupted-admission"), POLICIES)).hasMessageContaining("admission interrupted"); }
        finally { Thread.interrupted(); }
    }

    @Test
    void memoryAdmissionIsGlobalAndWaitingInvocationIsCancellable() throws Exception {
        var limits = limits(1);
        limits = new DocumentPreparationLimits(limits.leaseBytes(), 64, 4096, 2048, 10485760, 20971520, 1);
        var factory = factory(limits);
        var entered = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var workspace = factory.open(command("held"), POLICIES)) {
            var future = executor.submit(() -> {
                entered.countDown();
                try (var another = factory.open(new ExtractionCommand("waiting", workspace.source(), true), POLICIES)) {
                    throw new AssertionError("memory lease must not be admitted while held");
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(future.isDone()).isFalse();
            assertThat(future.cancel(true)).isTrue();
            workspace.discard();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
        try (var workspace = factory.open(command("after-cancel"), POLICIES)) { workspace.discard(); }
    }

    @Test
    void expiredSealsAreReapedButLivePinsArePreserved() throws Exception {
        var factory = factory(limits(1));
        var old = command("old");
        try (var workspace = factory.open(old, POLICIES)) { workspace.seal(descriptors(), summary(0)); }
        Files.setLastModifiedTime(pin(old).resolve("seal"), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        try (var live = factory.open(command("live"), POLICIES)) {
            live.seal(descriptors(), summary(0));
            var livePin = pin(new ExtractionCommand("live", live.source(), true));
            Files.setLastModifiedTime(livePin.resolve("seal"), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
            try (var another = factory.open(command("next"), POLICIES)) {
                assertThat(pin(old)).doesNotExist();
                assertThat(livePin).exists();
                another.discard();
            }
            live.discard();
        }
    }

    private ExtractionCommand command(String id) throws Exception {
        Path source = temporary.resolve(id.replace('.', '-') + ".html");
        Files.writeString(source, "<p>fixture</p>");
        return new ExtractionCommand(id, source, false);
    }
    private Path pin(ExtractionCommand command) { return temporary.resolve("workspace").resolve(ArtifactIdentityDefinition.sha256(command.runId())); }
    private JdbcDocumentPreparationWorkspaceFactory factory(DocumentPreparationLimits limits) {
        return new JdbcDocumentPreparationWorkspaceFactory(temporary.resolve("workspace"), limits, identities(), "policy");
    }
    private ArtifactIdentityResolver identities() {
        return new CanonicalArtifactIdentityResolver(List.of(new ArtifactIdentityDefinition("first", List.of("value"), false, 1),
                new ArtifactIdentityDefinition("last", List.of("value"), false, 1)));
    }
    private DocumentPreparationLimits limits(int batch) { return new DocumentPreparationLimits(2097152, 64, 4096, 2048, 10485760, 104857600, batch); }
    private ArtifactIdSequence ids() { return new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 100); }
    private List<ArtifactWritePlan> descriptors() { return List.of(new ArtifactWritePlan("first", HEADER, List.of(), ids()), new ArtifactWritePlan("last", HEADER, List.of(), ids())); }
    private DocumentPreparationSummary summary(int size) { return new DocumentPreparationSummary(size, size, DiagnosticSummary.empty()); }
    private RoutedArtifactCandidate candidate(String artifact, String key, String name) { return new RoutedArtifactCandidate(artifact, row(key, name, "source", 0)); }
    private PreparedArtifactRow row(String key, String name, String source, int ordinal) {
        var values = new LinkedHashMap<String, String>();
        values.put("id", null); values.put("value", key); values.put("name", name); values.put("source", source);
        return new PreparedArtifactRow(ArtifactRow.ordered(values), Optional.of("id"), Map.of("name", new OccurrencePosition(ordinal)));
    }
}
