package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.iocextractor.adapter.out.transport.smb.SmbContractTestSupport.holdWriterWithoutDeleteSharing;
import static com.iocextractor.adapter.out.transport.smb.SmbContractTestSupport.remoteRoot;
import static com.iocextractor.adapter.out.transport.smb.SmbContractTestSupport.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "ioc.smb.contract", matches = "true")
class SmbManagedImportContractTest {

    private static final ImportSourceId SOURCE = new ImportSourceId("live-smb-feed");

    @TempDir
    Path tempDir;

    @Test
    void claimsByServerRenameAndRetriesAfterProducerSharingConflict() throws IOException {
        SmbEndpointSettings endpoint = settings();
        String root = uniqueRoot("share-mode");
        String producer = SmbFileTransport.join(root, "feed.csv");
        ImportDeliveryId deliveryId = new ImportDeliveryId(UUID.randomUUID().toString());
        Path input = Files.writeString(tempDir.resolve("feed.csv"),
                "ip\n192.0.2.1\n", StandardCharsets.UTF_8);

        try (SmbShareClient client = new SmbjShareClientFactory().open(endpoint);
             SmbSessionPool pool = new SmbSessionPool(List.of(endpoint))) {
            provision(client, root);
            client.upload(input, producer);
            SmbManagedImportSourceLifecycle lifecycle = lifecycle(root, pool);
            ImportSourceCandidate candidate = lifecycle.detect(SOURCE, Instant.now()).getFirst();

            try (var ignored = holdWriterWithoutDeleteSharing(client, producer)) {
                assertThatThrownBy(() -> lifecycle.claim(new ClaimImportSourceCommand(
                        deliveryId, SOURCE, candidate.candidateToken())))
                        .isInstanceOf(RuntimeException.class);
            }

            assertThat(client.fileExists(producer)).isTrue();
            var claimed = lifecycle.claim(new ClaimImportSourceCommand(
                    deliveryId, SOURCE, candidate.candidateToken()));
            assertThat(Files.readString(lifecycle.resolveSnapshot(claimed.snapshot().reference())))
                    .isEqualTo("ip\n192.0.2.1\n");
            assertThat(client.fileExists(producer)).isFalse();

            lifecycle.disposition(new DispositionImportSourceCommand(
                    deliveryId, SOURCE, ImportTerminalOutcome.SUCCEEDED));
            assertThat(client.fileExists(outcomePath(root, "terminal", deliveryId))).isTrue();
        } finally {
            cleanup(endpoint, root);
        }
    }

    @Test
    void destinationCollisionPreservesBothRemoteObjectsAndPublishesNoSnapshot() throws IOException {
        SmbEndpointSettings endpoint = settings();
        String root = uniqueRoot("collision");
        String producer = SmbFileTransport.join(root, "feed.csv");
        ImportDeliveryId deliveryId = new ImportDeliveryId(UUID.randomUUID().toString());
        Path producerFile = Files.writeString(tempDir.resolve("producer.csv"), "producer");
        Path collisionFile = Files.writeString(tempDir.resolve("collision.csv"), "collision");

        try (SmbShareClient client = new SmbjShareClientFactory().open(endpoint);
             SmbSessionPool pool = new SmbSessionPool(List.of(endpoint))) {
            provision(client, root);
            client.upload(producerFile, producer);
            SmbManagedImportSourceLifecycle lifecycle = lifecycle(root, pool);
            ImportSourceCandidate candidate = lifecycle.detect(SOURCE, Instant.now()).getFirst();
            String processing = outcomePath(root, "processing", deliveryId);
            client.createDirectories(parent(processing));
            client.upload(collisionFile, processing);

            assertThatThrownBy(() -> lifecycle.claim(new ClaimImportSourceCommand(
                    deliveryId, SOURCE, candidate.candidateToken())))
                    .isInstanceOf(RuntimeException.class);

            assertThat(client.fileExists(producer)).isTrue();
            assertThat(client.fileExists(processing)).isTrue();
            assertThat(tempDir.resolve("snapshots")
                    .resolve(deliveryToken(deliveryId)).resolve("snapshot.csv"))
                    .doesNotExist();
        } finally {
            cleanup(endpoint, root);
        }
    }

    @Test
    void newSessionAdoptsRenameOrphanAndCompletesQuarantineDisposition() throws IOException {
        SmbEndpointSettings endpoint = settings();
        String root = uniqueRoot("orphan");
        String producer = SmbFileTransport.join(root, "feed.csv");
        ImportDeliveryId deliveryId = new ImportDeliveryId(UUID.randomUUID().toString());
        String processing = outcomePath(root, "processing", deliveryId);
        Path input = Files.writeString(tempDir.resolve("orphan.csv"),
                "hash_md5\n44D88612FEA8A8F36DE82E1278ABB02F\n");
        ImportSourceCandidate candidate;

        try {
            try (SmbShareClient client = new SmbjShareClientFactory().open(endpoint);
                 SmbSessionPool firstPool = new SmbSessionPool(List.of(endpoint))) {
                provision(client, root);
                client.upload(input, producer);
                candidate = lifecycle(root, firstPool).detect(SOURCE, Instant.now()).getFirst();
                client.createDirectories(parent(processing));
                client.rename(producer, processing);
            }

            try (SmbSessionPool recoveredPool = new SmbSessionPool(List.of(endpoint))) {
                SmbManagedImportSourceLifecycle recovered = lifecycle(root, recoveredPool);
                var claimed = recovered.claim(new ClaimImportSourceCommand(
                        deliveryId, SOURCE, candidate.candidateToken()));
                assertThat(Files.readAllBytes(recovered.resolveSnapshot(claimed.snapshot().reference())))
                        .isEqualTo(Files.readAllBytes(input));

                recovered.disposition(new DispositionImportSourceCommand(
                        deliveryId, SOURCE, ImportTerminalOutcome.REJECTED));
            }

            try (SmbShareClient client = new SmbjShareClientFactory().open(endpoint)) {
                assertThat(client.fileExists(processing)).isFalse();
                assertThat(client.fileExists(outcomePath(root, "quarantine", deliveryId))).isTrue();
            }
        } finally {
            cleanup(endpoint, root);
        }
    }

    private SmbManagedImportSourceLifecycle lifecycle(String root, SmbSessionPool pool) {
        return new SmbManagedImportSourceLifecycle(
                List.of(new SmbImportSourceDefinition(SOURCE, "contract", root)),
                pool,
                new TestImportSnapshotStore(tempDir.resolve("snapshots")),
                Duration.ZERO,
                1024 * 1024);
    }

    private void provision(SmbShareClient client, String root) {
        for (String phase : List.of("processing", "terminal", "quarantine", "probe")) {
            client.createDirectories(SmbFileTransport.join(
                    SmbFileTransport.join(root, ".ioc-managed-import"), phase));
        }
    }

    private static String uniqueRoot(String scenario) {
        return SmbFileTransport.join(
                remoteRoot(), "p8-managed-import-" + scenario + '-' + UUID.randomUUID());
    }

    private static String outcomePath(
            String root,
            String phase,
            ImportDeliveryId deliveryId) {
        return root + "/.ioc-managed-import/" + phase + '/' + deliveryToken(deliveryId) + ".csv";
    }

    private static String parent(String remotePath) {
        return remotePath.substring(0, remotePath.lastIndexOf('/'));
    }

    private static String deliveryToken(ImportDeliveryId deliveryId) {
        return SmbManagedImportSourceLifecycle.deliveryToken(deliveryId);
    }

    private static void cleanup(SmbEndpointSettings endpoint, String root) {
        try (SmbShareClient client = new SmbjShareClientFactory().open(endpoint)) {
            client.deleteTree(root);
        }
    }
}
