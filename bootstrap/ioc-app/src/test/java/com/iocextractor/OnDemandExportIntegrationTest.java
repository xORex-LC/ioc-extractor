package com.iocextractor;

import com.iocextractor.adapter.in.cli.IocRootCommand;
import com.iocextractor.adapter.out.sink.csv.FileSystemCompletedSliceCatalog;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcPublishLedger;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.export.ExportRunRecoveryService;
import com.iocextractor.application.export.ExportService;
import com.iocextractor.application.export.SliceDescriptor;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportOperationGuard;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.sync.ArtifactPublishService;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishLedgerSliceRetentionGuard;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.Retrier;
import com.iocextractor.application.sync.RetryPolicy;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.bootstrap.ExportPlanCatalog;
import com.iocextractor.bootstrap.LazyServiceStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Full artifact-emission path from canonical JDBC writes to verified immutable slices. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OnDemandExportIntegrationTest {

    private static final Path TEST_ROOT = Path.of("target", "export-e2e-" + UUID.randomUUID());
    private static final Path EXPORT_ROOT = TEST_ROOT.resolve("export");

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("ioc.storage.service.url",
                () -> "jdbc:sqlite:" + TEST_ROOT.resolve("service.db"));
        registry.add("ioc.storage.dataframe.url",
                () -> "jdbc:sqlite:" + TEST_ROOT.resolve("dataframe.db"));
        registry.add("ioc.lookup.path", () -> TEST_ROOT.resolve("missing-lookup.csv").toString());
        registry.add("ioc.export.root", EXPORT_ROOT::toString);
        registry.add("ioc.export.profiles[0].name", () -> "e2e-reputation");
        registry.add("ioc.export.profiles[0].output-mode", () -> "complete");
        registry.add("ioc.export.profiles[0].artifacts[0]", () -> "masks");
        registry.add("spring.main.banner-mode", () -> "off");
    }

    @Autowired
    JdbcCanonicalArtifactRepository canonical;

    @Autowired
    ExportPlanCatalog plans;

    @Autowired
    IocRootCommand rootCommand;

    @Autowired
    IFactory commandFactory;

    @Autowired
    SliceManifestCodec manifestCodec;

    @Autowired
    ArtifactRevisionReader revisionReader;

    @Autowired
    ExportProgressStore progressStore;

    @Autowired
    ExportRunLedger runLedger;

    @Autowired
    SnapshotSliceReader snapshotReader;

    @Autowired
    ArtifactSliceWriter sliceWriter;

    @Autowired
    ExportRunRecoveryService recoveryService;

    @Autowired
    ExportOperationGuard operationGuard;

    @Autowired
    Clock clock;

    @Autowired
    LazyServiceStorage serviceStorage;

    @Test
    void canonicalWriteExportsGoldenSliceSkipsDuplicatesAndCatchesConcurrentCommit() throws Exception {
        var plan = plans.plans().stream()
                .filter(candidate -> candidate.profile().name().equals("e2e-reputation"))
                .findFirst().orElseThrow();
        writeMask(plan, "1", "example.org");

        int first = new CommandLine(rootCommand, commandFactory)
                .execute("export", "--profile", "e2e-reputation");

        assertThat(first).isZero();
        List<Path> slices;
        try (var paths = Files.list(EXPORT_ROOT.resolve("e2e-reputation"))) {
            slices = paths.filter(Files::isDirectory).toList();
        }
        assertThat(slices).hasSize(1);
        Path slice = slices.getFirst();
        byte[] manifestBytes = Files.readAllBytes(slice.resolve("manifest.json"));
        SliceManifest manifest = manifestCodec.decode(manifestBytes);
        assertThat(manifest.profile()).isEqualTo("e2e-reputation");
        assertThat(manifest.artifacts()).singleElement().satisfies(entry -> {
            assertThat(entry.artifactName()).isEqualTo("masks");
            assertThat(entry.rows()).isEqualTo(1);
            assertThat(entry.coverage().revision()).isEqualTo(1);
        });
        assertThat(Files.readString(slice.resolve("masks_list_generated.csv")))
                .isEqualTo(golden("golden/expected-export-masks.csv"));
        assertThat(Files.readString(slice.resolve("_SUCCESS"), StandardCharsets.US_ASCII))
                .isEqualTo(sha256(manifestBytes) + "\n");

        publishSliceIsIdempotentAndReleasesRetention(slice, manifest);

        int second = new CommandLine(rootCommand, commandFactory)
                .execute("export", "--profile", "e2e-reputation");

        assertThat(second).isZero();
        try (var paths = Files.list(EXPORT_ROOT.resolve("e2e-reputation"))) {
            assertThat(paths.filter(Files::isDirectory).toList()).hasSize(1);
        }

        writeMask(plan, "2", "before-snapshot.example");
        var began = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = new ExportService(
                List.of(plan), revisionReader, progressStore, runLedger,
                blockingReader(began, release), sliceWriter,
                recoveryService, operationGuard, clock);

        ExportArtifactsResult during;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> service.export(
                    new ExportArtifactsCommand("e2e-reputation")));
            assertThat(began.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                writeMask(plan, "3", "after-snapshot.example");
            } finally {
                release.countDown();
            }
            during = future.get(5, TimeUnit.SECONDS);
        }

        assertThat(during.status()).isEqualTo(ExportRunStatus.COMPLETED);
        Path duringSlice = EXPORT_ROOT.resolve("e2e-reputation").resolve(during.sliceName());
        SliceManifest duringManifest = manifestCodec.decode(
                Files.readAllBytes(duringSlice.resolve("manifest.json")));
        assertThat(duringManifest.artifacts().getFirst().coverage().revision()).isEqualTo(2);
        assertThat(Files.readString(duringSlice.resolve("masks_list_generated.csv")))
                .contains("before-snapshot.example")
                .doesNotContain("after-snapshot.example");

        var catchUp = service.export(new ExportArtifactsCommand("e2e-reputation"));

        assertThat(catchUp.status()).isEqualTo(ExportRunStatus.COMPLETED);
        Path catchUpSlice = EXPORT_ROOT.resolve("e2e-reputation").resolve(catchUp.sliceName());
        SliceManifest catchUpManifest = manifestCodec.decode(
                Files.readAllBytes(catchUpSlice.resolve("manifest.json")));
        assertThat(catchUpManifest.artifacts().getFirst().coverage().revision()).isEqualTo(3);
        assertThat(Files.readString(catchUpSlice.resolve("masks_list_generated.csv")))
                .contains("after-snapshot.example");
    }

    private void publishSliceIsIdempotentAndReleasesRetention(Path slice, SliceManifest manifest)
            throws Exception {
        PublishTarget target = new PublishTarget(
                "e2e-target", "local", "/published", "e2e-reputation");
        JdbcPublishLedger ledger = new JdbcPublishLedger(serviceStorage.dataSource(), clock);
        PublishLedgerSliceRetentionGuard guard = new PublishLedgerSliceRetentionGuard(
                ledger, List.of(target));
        SliceDescriptor descriptor = new SliceDescriptor(
                manifest.sliceId(), manifest.profile(), slice.getFileName().toString(), manifest.createdAt());
        LocalPublishTransport transport = new LocalPublishTransport(TEST_ROOT.resolve("remote"));
        ArtifactPublishService publisher = new ArtifactPublishService(
                new FileSystemCompletedSliceCatalog(EXPORT_ROOT, manifestCodec),
                ledger,
                transport,
                List.of(target),
                new Retrier(new RetryPolicy(
                        1, java.time.Duration.ofMillis(1), 1.0d,
                        java.time.Duration.ofMillis(1), false), ignored -> { }),
                new CollectingDiagnosticSink(),
                clock);

        assertThat(guard.canDelete(descriptor)).isFalse();
        var command = new com.iocextractor.application.port.in.sync.ArtifactPublishCommand(
                Optional.of("e2e-reputation"), false);
        publisher.reconcile(command);
        var first = publisher.publish(command);
        var duplicate = publisher.publish(command);

        Path remoteSlice = TEST_ROOT.resolve("remote/local/published").resolve(slice.getFileName());
        assertThat(first.succeeded()).isOne();
        assertThat(duplicate.succeeded()).isOne();
        assertThat(transport.publishCalls).isOne();
        assertThat(guard.canDelete(descriptor)).isTrue();
        assertThat(Files.readAllBytes(remoteSlice.resolve("manifest.json")))
                .isEqualTo(Files.readAllBytes(slice.resolve("manifest.json")));
        assertThat(Files.readAllBytes(remoteSlice.resolve("masks_list_generated.csv")))
                .isEqualTo(Files.readAllBytes(slice.resolve("masks_list_generated.csv")));
        assertThat(Files.readString(remoteSlice.resolve("_SUCCESS"), StandardCharsets.US_ASCII))
                .isEqualTo(Files.readString(slice.resolve("_SUCCESS"), StandardCharsets.US_ASCII));
    }

    private void writeMask(ExportPlan plan, String id, String mask) {
        var artifact = plan.artifacts().getFirst();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        artifact.columns().forEach(column -> values.put(column, null));
        values.put("id", id);
        values.put("mask", mask);
        canonical.write("masks", new CanonicalArtifact(
                "masks", artifact.columns(), List.of(ArtifactRow.ordered(values))));
    }

    private SnapshotSliceReader blockingReader(CountDownLatch began, CountDownLatch release) {
        return (request, consumer) -> snapshotReader.stream(
                request, new BlockingConsumer(consumer, began, release));
    }

    private String golden(String resource) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing golden resource: " + resource);
            }
            String expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return expected.replace("\n", "\r\n");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class LocalPublishTransport implements FileTransport {
        private final Path root;
        private int publishCalls;

        private LocalPublishTransport(Path root) {
            this.root = root;
        }

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            return List.of();
        }

        @Override
        public Optional<RemoteObject> stat(String endpoint, String remotePath) {
            Path path = resolve(endpoint, remotePath);
            try {
                return Files.exists(path)
                        ? Optional.of(new RemoteObject(remotePath, Files.size(path), Files.getLastModifiedTime(path).toInstant()))
                        : Optional.empty();
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        @Override
        public void get(String endpoint, String remotePath, Path localDestination) {
            try {
                Files.copy(resolve(endpoint, remotePath), localDestination, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        @Override
        public void delete(String endpoint, String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
            publishCalls++;
            Path destination = resolve(request.endpoint(), request.remotePath());
            Path staging = destination.resolveSibling(destination.getFileName() + ".uploading");
            try {
                copyTree(request.localDirectory(), staging, request.commitMarkerName());
                Files.createDirectories(destination.getParent());
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                return new PublishReceipt(request.remotePath(), "local-e2e-verified");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        private void copyTree(Path source, Path destination, String marker) throws java.io.IOException {
            Files.createDirectories(destination);
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(candidate -> !candidate.equals(source)).toList()) {
                    Path relative = source.relativize(path);
                    if (relative.toString().equals(marker)) {
                        continue;
                    }
                    Path target = destination.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(path, target);
                    }
                }
            }
            Files.copy(source.resolve(marker), destination.resolve(marker));
        }

        private Path resolve(String endpoint, String remotePath) {
            String relative = remotePath.startsWith("/") ? remotePath.substring(1) : remotePath;
            return root.resolve(endpoint).resolve(relative).normalize();
        }
    }

    private record BlockingConsumer(SnapshotRowConsumer delegate,
                                    CountDownLatch began,
                                    CountDownLatch release) implements SnapshotRowConsumer {

        @Override
        public void begin(SnapshotMetadata metadata) {
            delegate.begin(metadata);
            began.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrent canonical commit");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding export snapshot", interrupted);
            }
        }

        @Override
        public void beginArtifact(SnapshotArtifactMetadata artifact) {
            delegate.beginArtifact(artifact);
        }

        @Override
        public void row(ArtifactRow row) {
            delegate.row(row);
        }

        @Override
        public void endArtifact() {
            delegate.endArtifact();
        }

        @Override
        public void end() {
            delegate.end();
        }
    }
}
