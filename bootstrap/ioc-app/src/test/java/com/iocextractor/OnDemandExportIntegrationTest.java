package com.iocextractor;

import com.iocextractor.adapter.in.cli.IocRootCommand;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Full C8 path: canonical JDBC row to CLI-created verified immutable local slice. */
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

    @Test
    void manualExportCreatesValidFinalSliceAndThenCheapSkips() throws Exception {
        var plan = plans.plans().stream()
                .filter(candidate -> candidate.profile().name().equals("e2e-reputation"))
                .findFirst().orElseThrow();
        var artifact = plan.artifacts().getFirst();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        artifact.columns().forEach(column -> values.put(column, null));
        values.put("id", "1");
        values.put("mask", "example.org");
        canonical.write("masks", new CanonicalArtifact(
                "masks", artifact.columns(), List.of(ArtifactRow.ordered(values))));

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
        assertThat(Files.readString(slice.resolve("_SUCCESS"), StandardCharsets.US_ASCII))
                .matches("[0-9a-f]{64}\\n");

        int second = new CommandLine(rootCommand, commandFactory)
                .execute("export", "--profile", "e2e-reputation");

        assertThat(second).isZero();
        try (var paths = Files.list(EXPORT_ROOT.resolve("e2e-reputation"))) {
            assertThat(paths.filter(Files::isDirectory).toList()).hasSize(1);
        }
    }
}
