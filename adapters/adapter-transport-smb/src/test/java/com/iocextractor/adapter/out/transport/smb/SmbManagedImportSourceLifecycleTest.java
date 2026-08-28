package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessStatus;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.PurgeImportTerminalSourceCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbManagedImportSourceLifecycleTest {

    private static final ImportSourceId SOURCE = new ImportSourceId("smb-feed");
    private static final ImportDeliveryId DELIVERY = new ImportDeliveryId("delivery-1");
    private static final Instant MODIFIED_AT = Instant.parse("2026-08-24T12:00:00.123456700Z");

    @TempDir
    Path tempDir;

    @Test
    void completePollingDetectsStableFileWithoutAnyNotification() {
        Fixture fixture = fixture(Duration.ofSeconds(2));
        fixture.remote.put("incoming/feed.csv", "ip;description\n192.0.2.1;bad\n", MODIFIED_AT);

        assertThat(fixture.lifecycle.detect(SOURCE, MODIFIED_AT)).isEmpty();

        assertThat(fixture.lifecycle.detect(SOURCE, MODIFIED_AT.plusSeconds(2)))
                .singleElement()
                .extracting(ImportSourceCandidate::sourceId)
                .isEqualTo(SOURCE);
    }

    @Test
    void serverRenamePrecedesDownloadAndPublishesDurableLocalSnapshot() throws IOException {
        Fixture fixture = fixture(Duration.ZERO);
        byte[] bytes = "ip\n192.0.2.1\n".getBytes(StandardCharsets.UTF_8);
        fixture.remote.put("incoming/feed.csv", bytes, MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);

        var result = fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken()));

        assertThat(fixture.remote.operations)
                .containsSubsequence(
                        "rename:incoming/feed.csv->incoming/.ioc-managed-import/processing/"
                                + deliveryToken(DELIVERY) + ".csv",
                        "download:incoming/.ioc-managed-import/processing/"
                                + deliveryToken(DELIVERY) + ".csv");
        assertThat(Files.readAllBytes(fixture.lifecycle.resolveSnapshot(
                result.snapshot().reference()))).isEqualTo(bytes);
        assertThat(fixture.remote.files).doesNotContainKey("incoming/feed.csv");
    }

    @Test
    void reconnectAdoptsClaimedOrphanWithoutRenamingProducerAgain() throws IOException {
        Fixture fixture = fixture(Duration.ZERO);
        byte[] bytes = "ip\n198.51.100.9\n".getBytes(StandardCharsets.UTF_8);
        fixture.remote.put("incoming/feed.csv", bytes, MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);
        fixture.remote.failNextDownload = true;

        assertThatThrownBy(() -> fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection reset");

        var recovered = fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken()));

        assertThat(Files.readAllBytes(fixture.lifecycle.resolveSnapshot(
                recovered.snapshot().reference()))).isEqualTo(bytes);
        assertThat(fixture.remote.operations.stream()
                .filter(operation -> operation.startsWith("rename:incoming/feed.csv")))
                .hasSize(1);
        assertThat(fixture.factory.opened).hasSize(2);
    }

    @Test
    void ownershipCollisionFailsClosedBeforeAnyDownload() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put("incoming/feed.csv", "first", MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);
        fixture.remote.put(processingPath(DELIVERY), "other", MODIFIED_AT);

        assertThatThrownBy(() -> fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("destination collision");

        assertThat(fixture.remote.operations).noneMatch(operation -> operation.startsWith("download:"));
        assertThat(fixture.remote.files).containsKeys("incoming/feed.csv", processingPath(DELIVERY));
    }

    @Test
    void producerSharingConflictFailsClosedBeforeAnyDownload() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put("incoming/feed.csv", "held-open", MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);
        fixture.remote.rejectRenameForSharing = true;

        assertThatThrownBy(() -> fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sharing violation");

        assertThat(fixture.remote.operations).noneMatch(operation -> operation.startsWith("download:"));
        assertThat(fixture.remote.files).containsKey("incoming/feed.csv");
    }

    @Test
    void sameMetadataObjectSubstitutionAfterRenameFailsClosed() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put("incoming/feed.csv", "same-size", MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);
        fixture.remote.substituteAfterRename = true;

        assertThatThrownBy(() -> fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not match reserved candidate");

        assertThat(fixture.remote.operations).noneMatch(operation -> operation.startsWith("download:"));
        assertThat(tempDir.resolve(deliveryToken(DELIVERY)).resolve("snapshot.csv"))
                .doesNotExist();
    }

    @Test
    void producerMutationDuringDownloadFailsWithoutPublishingSnapshot() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put("incoming/feed.csv", "same-size", MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);
        fixture.remote.mutateModifiedAtAfterDownload = true;

        assertThatThrownBy(() -> fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("changed during materialization");

        assertThat(tempDir.resolve(deliveryToken(DELIVERY)).resolve("snapshot.csv"))
                .doesNotExist();
    }

    @Test
    void terminalRemoteDispositionIsIdempotentAndCollisionSafe() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put("incoming/feed.csv", "ip\n203.0.113.4\n", MODIFIED_AT);
        ImportSourceCandidate candidate = candidate(fixture);
        fixture.lifecycle.claim(new ClaimImportSourceCommand(
                DELIVERY, SOURCE, candidate.candidateToken()));
        var command = new DispositionImportSourceCommand(
                DELIVERY, SOURCE, ImportTerminalOutcome.SUCCEEDED);

        fixture.lifecycle.disposition(command);
        fixture.lifecycle.disposition(command);

        String terminal = "incoming/.ioc-managed-import/terminal/" + deliveryToken(DELIVERY) + ".csv";
        assertThat(fixture.remote.files).containsKey(terminal).doesNotContainKey(processingPath(DELIVERY));
    }

    @Test
    void capabilityProbeRequiresPreProvisionedNamespaceAndNeverCreatesDirectories() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.directories.remove("incoming/.ioc-managed-import/probe");

        var readiness = fixture.lifecycle.probe(SOURCE);

        assertThat(readiness.status()).isEqualTo(ImportSourceReadinessStatus.INCOMPATIBLE);
        assertThat(readiness.diagnosticCode()).isEqualTo("IMPORT.SOURCE_NAMESPACE_INCOMPATIBLE");
        assertThat(fixture.remote.operations).noneMatch(value -> value.startsWith("mkdir:"));
    }

    @Test
    void capabilityProbeUsesPrivateNoReplaceFlowAndExactDelete() {
        Fixture fixture = fixture(Duration.ZERO);

        var readiness = fixture.lifecycle.probe(SOURCE);

        assertThat(readiness.status()).isEqualTo(ImportSourceReadinessStatus.READY);
        assertThat(fixture.remote.operations).anySatisfy(operation ->
                assertThat(operation).startsWith("create-empty:incoming/.ioc-managed-import/probe/"));
        assertThat(fixture.remote.operations).anySatisfy(operation ->
                assertThat(operation).startsWith("delete-file:incoming/.ioc-managed-import/terminal/"));
    }

    @Test
    void retentionDeletesOnlyExpectedTerminalFileAndAbsenceIsIdempotent() {
        Fixture fixture = fixture(Duration.ZERO);
        String terminal = "incoming/.ioc-managed-import/terminal/"
                + deliveryToken(DELIVERY) + ".csv";
        fixture.remote.put(terminal, "retained", MODIFIED_AT);
        var command = new PurgeImportTerminalSourceCommand(
                DELIVERY, SOURCE, ImportManagedObjectId.from(DELIVERY),
                ImportTerminalOutcome.SUCCEEDED);

        fixture.lifecycle.purge(command);
        fixture.lifecycle.purge(command);

        assertThat(fixture.remote.files).doesNotContainKey(terminal);
        assertThat(fixture.remote.operations).contains("delete-file:" + terminal);
    }

    @Test
    void retentionFailsClosedWhileManagedObjectStillExistsInProcessing() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put(processingPath(DELIVERY), "claimed", MODIFIED_AT);
        var command = new PurgeImportTerminalSourceCommand(
                DELIVERY, SOURCE, ImportManagedObjectId.from(DELIVERY),
                ImportTerminalOutcome.SUCCEEDED);

        assertThatThrownBy(() -> fixture.lifecycle.purge(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("contradictory");
        assertThat(fixture.remote.files).containsKey(processingPath(DELIVERY));
    }

    @Test
    void retentionRejectsReparsePointWithoutDeletingIt() {
        Fixture fixture = fixture(Duration.ZERO);
        String terminal = "incoming/.ioc-managed-import/terminal/"
                + deliveryToken(DELIVERY) + ".csv";
        fixture.remote.put(terminal, "link-like", MODIFIED_AT);
        fixture.remote.reparsePoints.add(terminal);
        var command = new PurgeImportTerminalSourceCommand(
                DELIVERY, SOURCE, ImportManagedObjectId.from(DELIVERY),
                ImportTerminalOutcome.SUCCEEDED);

        assertThatThrownBy(() -> fixture.lifecycle.purge(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not a regular file");
        assertThat(fixture.remote.files).containsKey(terminal);
        assertThat(fixture.remote.operations).doesNotContain("delete-file:" + terminal);
    }

    @Test
    void syncTransportAndManagedImportReuseOneEndpointSession() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.remote.put("incoming/feed.csv", "ip\n192.0.2.8\n", MODIFIED_AT);
        var sync = new SmbFileTransport(fixture.sessions);

        sync.list("primary", "incoming");
        fixture.lifecycle.detect(SOURCE, MODIFIED_AT);

        assertThat(fixture.factory.opened).hasSize(1);
    }

    private ImportSourceCandidate candidate(Fixture fixture) {
        return fixture.lifecycle.detect(SOURCE, MODIFIED_AT).getFirst();
    }

    private Fixture fixture(Duration quietPeriod) {
        RemoteState remote = new RemoteState();
        remote.directories.addAll(Set.of(
                "incoming",
                "incoming/.ioc-managed-import",
                "incoming/.ioc-managed-import/processing",
                "incoming/.ioc-managed-import/terminal",
                "incoming/.ioc-managed-import/quarantine",
                "incoming/.ioc-managed-import/probe"));
        FakeFactory factory = new FakeFactory(remote);
        SmbSessionPool sessions = new SmbSessionPool(
                List.of(settings()), factory,
                Clock.fixed(MODIFIED_AT, ZoneOffset.UTC));
        SmbManagedImportSourceLifecycle lifecycle = new SmbManagedImportSourceLifecycle(
                List.of(new SmbImportSourceDefinition(SOURCE, "primary", "incoming")),
                sessions, new TestImportSnapshotStore(tempDir), quietPeriod, 1024 * 1024);
        return new Fixture(remote, factory, sessions, lifecycle);
    }

    private static SmbEndpointSettings settings() {
        return new SmbEndpointSettings(
                "primary", "files.example.test", "share", "", "user",
                "secret".toCharArray(), SmbEncryptionPolicy.DISABLED, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    private static String processingPath(ImportDeliveryId deliveryId) {
        return "incoming/.ioc-managed-import/processing/" + deliveryToken(deliveryId) + ".csv";
    }

    private static String deliveryToken(ImportDeliveryId deliveryId) {
        return SmbManagedImportSourceLifecycle.deliveryToken(deliveryId);
    }

    private record Fixture(RemoteState remote,
                           FakeFactory factory,
                           SmbSessionPool sessions,
                           SmbManagedImportSourceLifecycle lifecycle) {
    }

    private static final class FakeFactory implements SmbShareClientFactory {
        private final RemoteState remote;
        private final List<FakeClient> opened = new ArrayList<>();

        private FakeFactory(RemoteState remote) {
            this.remote = remote;
        }

        @Override
        public SmbShareClient open(SmbEndpointSettings settings) {
            FakeClient client = new FakeClient(remote);
            opened.add(client);
            return client;
        }
    }

    private static final class FakeClient implements SmbShareClient {
        private final RemoteState remote;

        private FakeClient(RemoteState remote) {
            this.remote = remote;
        }

        @Override
        public List<SmbRemoteEntry> list(String remotePath) {
            String prefix = remotePath + "/";
            return remote.files.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .filter(entry -> !entry.getKey().substring(prefix.length()).contains("/"))
                    .map(entry -> new SmbRemoteEntry(entry.getKey(), entry.getValue().bytes().length,
                            entry.getValue().modifiedAt(), false,
                            remote.reparsePoints.contains(entry.getKey()), entry.getValue().fileId()))
                    .toList();
        }

        @Override
        public Optional<SmbRemoteEntry> stat(String remotePath) {
            StoredFile file = remote.files.get(remotePath);
            return file == null ? Optional.empty() : Optional.of(new SmbRemoteEntry(
                    remotePath, file.bytes().length, file.modifiedAt(), false,
                    remote.reparsePoints.contains(remotePath), file.fileId()));
        }

        @Override
        public void download(String remotePath, Path localDestination) {
            remote.operations.add("download:" + remotePath);
            if (remote.failNextDownload) {
                remote.failNextDownload = false;
                throw new RuntimeException("connection reset during download");
            }
            StoredFile file = remote.files.get(remotePath);
            try {
                Files.write(localDestination, file.bytes());
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
            if (remote.mutateModifiedAtAfterDownload) {
                remote.files.put(remotePath,
                        new StoredFile(file.bytes(), file.modifiedAt().plusSeconds(1), file.fileId()));
            }
        }

        @Override
        public void delete(String remotePath) {
            remote.files.remove(remotePath);
            remote.directories.remove(remotePath);
        }

        @Override
        public void deleteRegularFile(String remotePath) {
            if (remote.reparsePoints.contains(remotePath)) {
                throw new IllegalArgumentException("SMB delete target is not a regular file");
            }
            remote.files.remove(remotePath);
            remote.reparsePoints.remove(remotePath);
            remote.operations.add("delete-file:" + remotePath);
        }

        @Override
        public boolean fileExists(String remotePath) {
            return remote.files.containsKey(remotePath);
        }

        @Override
        public boolean directoryExists(String remotePath) {
            return remote.directories.contains(remotePath);
        }

        @Override
        public String readText(String remotePath) {
            return new String(remote.files.get(remotePath).bytes(), StandardCharsets.UTF_8);
        }

        @Override
        public void createDirectories(String remotePath) {
            String[] parts = remotePath.split("/");
            StringBuilder current = new StringBuilder();
            for (String part : parts) {
                if (!current.isEmpty()) {
                    current.append('/');
                }
                current.append(part);
                remote.directories.add(current.toString());
            }
        }

        @Override
        public void createEmptyFile(String remotePath) {
            if (remote.files.containsKey(remotePath) || remote.directories.contains(remotePath)) {
                throw new IllegalStateException("probe collision");
            }
            remote.put(remotePath, new byte[0], MODIFIED_AT);
            remote.operations.add("create-empty:" + remotePath);
        }

        @Override
        public void upload(Path localFile, String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rename(String sourcePath, String targetPath) {
            if (remote.rejectRenameForSharing) {
                throw new RuntimeException("SMB sharing violation");
            }
            if (remote.files.containsKey(targetPath) || remote.directories.contains(targetPath)) {
                throw new RuntimeException("rename destination collision");
            }
            StoredFile file = remote.files.remove(sourcePath);
            if (file == null) {
                throw new RuntimeException("rename source absent");
            }
            remote.files.put(targetPath, file);
            if (remote.substituteAfterRename) {
                remote.put(targetPath, file.bytes(), file.modifiedAt());
            }
            remote.operations.add("rename:" + sourcePath + "->" + targetPath);
        }

        @Override
        public void deleteTree(String remotePath) {
            remote.files.keySet().removeIf(path -> path.equals(remotePath)
                    || path.startsWith(remotePath + "/"));
            remote.directories.removeIf(path -> path.equals(remotePath)
                    || path.startsWith(remotePath + "/"));
        }

        @Override
        public void close() {
            // A reconnect creates a new client over the same remote share state.
        }
    }

    private static final class RemoteState {
        private final Map<String, StoredFile> files = new HashMap<>();
        private final Set<String> directories = new HashSet<>();
        private final Set<String> reparsePoints = new HashSet<>();
        private final List<String> operations = new ArrayList<>();
        private long nextFileId = 1L;
        private boolean failNextDownload;
        private boolean mutateModifiedAtAfterDownload;
        private boolean rejectRenameForSharing;
        private boolean substituteAfterRename;

        private void put(String path, String value, Instant modifiedAt) {
            put(path, value.getBytes(StandardCharsets.UTF_8), modifiedAt);
        }

        private void put(String path, byte[] bytes, Instant modifiedAt) {
            files.put(path, new StoredFile(bytes.clone(), modifiedAt, nextFileId++));
        }
    }

    private record StoredFile(byte[] bytes, Instant modifiedAt, long fileId) {
    }
}
