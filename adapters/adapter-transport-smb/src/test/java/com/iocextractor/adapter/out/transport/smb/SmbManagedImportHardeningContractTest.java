package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessStatus;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.PurgeImportTerminalSourceCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mock-free qualification of the operator-provisioned two-identity namespace. */
@EnabledIfSystemProperty(named = "ioc.smb.hardening.contract", matches = "true")
class SmbManagedImportHardeningContractTest {

    private static final ImportSourceId SOURCE = new ImportSourceId("live-smb-hardened");

    @TempDir
    Path tempDir;

    @Test
    void preProvisionedNamespaceSupportsCapabilityReconnectAndExactRetention()
            throws Exception {
        SmbEndpointSettings service = SmbContractTestSupport.settings();
        SmbEndpointSettings producer = producerSettings();
        String root = require("ioc.smb.hardening.remotePath");
        String privateRoot = SmbFileTransport.join(root, ".ioc-managed-import");
        String producerPath = SmbFileTransport.join(
                root, "hardening-" + UUID.randomUUID() + ".csv");
        ImportDeliveryId deliveryId = new ImportDeliveryId(UUID.randomUUID().toString());
        String token = SmbManagedImportSourceLifecycle.deliveryToken(deliveryId) + ".csv";
        String processing = SmbFileTransport.join(
                SmbFileTransport.join(privateRoot, "processing"), token);
        String terminal = SmbFileTransport.join(
                SmbFileTransport.join(privateRoot, "terminal"), token);
        Path source = Files.writeString(tempDir.resolve("candidate.csv"),
                "ip\n192.0.2.44\n");

        try {
            assertProducerCannotAccessPrivateNamespace(producer, privateRoot);
            assertMissingNamespaceIsIncompatible(service, root);

            try (SmbShareClient producerClient = new SmbjShareClientFactory().open(producer)) {
                producerClient.upload(source, producerPath);
            }

            try (SmbSessionPool first = new SmbSessionPool(List.of(service))) {
                SmbManagedImportSourceLifecycle lifecycle = lifecycle(root, first);
                assertThat(lifecycle.probe(SOURCE).status())
                        .isEqualTo(ImportSourceReadinessStatus.READY);
                var candidate = lifecycle.detect(SOURCE, Instant.now()).getFirst();
                lifecycle.claim(new ClaimImportSourceCommand(
                        deliveryId, SOURCE, candidate.candidateToken()));
            }

            try (SmbSessionPool reconnected = new SmbSessionPool(List.of(service))) {
                SmbManagedImportSourceLifecycle lifecycle = lifecycle(root, reconnected);
                lifecycle.disposition(new DispositionImportSourceCommand(
                        deliveryId, SOURCE, ImportTerminalOutcome.SUCCEEDED));
                lifecycle.purge(new PurgeImportTerminalSourceCommand(
                        deliveryId, SOURCE, ImportManagedObjectId.from(deliveryId),
                        ImportTerminalOutcome.SUCCEEDED));
            }

            try (SmbShareClient serviceClient = new SmbjShareClientFactory().open(service)) {
                assertThat(serviceClient.fileExists(producerPath)).isFalse();
                assertThat(serviceClient.fileExists(processing)).isFalse();
                assertThat(serviceClient.fileExists(terminal)).isFalse();
            }
        } finally {
            cleanupExact(service, producerPath, processing, terminal);
        }
    }

    private SmbManagedImportSourceLifecycle lifecycle(String root, SmbSessionPool sessions) {
        return new SmbManagedImportSourceLifecycle(
                List.of(new SmbImportSourceDefinition(SOURCE, "contract", root)),
                sessions,
                new TestImportSnapshotStore(tempDir.resolve("snapshots")),
                Duration.ZERO,
                1024 * 1024);
    }

    private void assertMissingNamespaceIsIncompatible(
            SmbEndpointSettings service, String provisionedRoot) {
        String absentRoot = provisionedRoot + "-absent-" + UUID.randomUUID();
        try (SmbSessionPool sessions = new SmbSessionPool(List.of(service))) {
            assertThat(lifecycle(absentRoot, sessions).probe(SOURCE).status())
                    .isEqualTo(ImportSourceReadinessStatus.INCOMPATIBLE);
        }
    }

    private void assertProducerCannotAccessPrivateNamespace(
            SmbEndpointSettings producer, String privateRoot) {
        try (SmbShareClient producerClient = new SmbjShareClientFactory().open(producer)) {
            assertThatThrownBy(() -> producerClient.list(privateRoot))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> producerClient.createEmptyFile(
                    SmbFileTransport.join(privateRoot, "producer-denial-probe")))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private void cleanupExact(
            SmbEndpointSettings service,
            String producerPath,
            String processing,
            String terminal) {
        try (SmbShareClient client = new SmbjShareClientFactory().open(service)) {
            client.deleteRegularFile(producerPath);
            client.deleteRegularFile(processing);
            client.deleteRegularFile(terminal);
        }
    }

    private static SmbEndpointSettings producerSettings() {
        char[] password = require("ioc.smb.producer.password").toCharArray();
        try {
            return new SmbEndpointSettings(
                    "producer-contract",
                    require("ioc.smb.host"),
                    SmbContractTestSupport.port(),
                    require("ioc.smb.share"),
                    System.getProperty("ioc.smb.producer.domain", ""),
                    require("ioc.smb.producer.username"),
                    password,
                    SmbContractTestSupport.encryptionPolicy(),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(1));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String require(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + property);
        }
        return value;
    }
}
