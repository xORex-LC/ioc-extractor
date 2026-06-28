package com.iocextractor;

import com.iocextractor.adapter.in.cli.IocRootCommand;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.export.ExportRunRecoveryService;
import com.iocextractor.application.export.ExportService;
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
import com.iocextractor.bootstrap.ExportPlanCatalog;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
