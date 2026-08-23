package com.iocextractor;

import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportRunRecoveryService;
import com.iocextractor.application.export.ExportService;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.ExportOperationGuard;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;
import com.iocextractor.bootstrap.ExportPlanCatalog;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end proof that immutable CSV slices preserve and later reuse sparse export slots. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReusableExportSlotIntegrationTest {

    private static final Path TEST_ROOT = Path.of("target", "export-slot-e2e-" + UUID.randomUUID());
    private static final Path EXPORT_ROOT = TEST_ROOT.resolve("export");

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("ioc.storage.service.url", () -> "jdbc:sqlite:" + TEST_ROOT.resolve("service.db"));
        registry.add("ioc.storage.dataframe.url", () -> "jdbc:sqlite:" + TEST_ROOT.resolve("dataframe.db"));
        registry.add("ioc.export.root", EXPORT_ROOT::toString);
        registry.add("ioc.export.profiles[0].name", () -> "slot-reputation");
        registry.add("ioc.export.profiles[0].output-mode", () -> "complete");
        registry.add("ioc.export.profiles[0].artifacts[0]", () -> "masks");
        registry.add("spring.main.banner-mode", () -> "off");
    }

    @Autowired
    JdbcCanonicalArtifactRepository canonical;

    @Autowired
    ExportPlanCatalog plans;

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
    LifecycleControlStore lifecycleControl;

    @Autowired
    @Qualifier("dataframeStorageDataSource")
    HikariDataSource dataframe;

    @Autowired
    Clock clock;

    @Test
    void historical_slice_remains_immutable_when_later_lifecycles_reuse_its_slots() throws Exception {
        ExportPlan plan = plans.plans().stream()
                .filter(candidate -> candidate.profile().name().equals("slot-reputation"))
                .findFirst()
                .orElseThrow();
        writeMasks(plan, Map.of(
                "1", "a.example",
                "2", "b.example",
                "3", "c.example",
                "4", "d.example",
                "5", "e.example"));
        Instant asOf = clock.instant();
        initializeLifecycles(asOf);
        activate(asOf);
        ExportService service = exportService(plan);

        var baseline = service.export(new ExportArtifactsCommand("slot-reputation"));
        assertThat(baseline.status()).isEqualTo(ExportRunStatus.COMPLETED);
        Path baselineCsv = sliceCsv(baseline.sliceName());
        String immutableBaseline = Files.readString(baselineCsv);
        assertThat(readIdByMask(baselineCsv)).containsExactly(
                Map.entry("a.example", "1"),
                Map.entry("b.example", "2"),
                Map.entry("c.example", "3"));

        changeActiveSet(asOf, List.of("a.example", "b.example"), List.of("d.example"));
        var afterFirstReuse = service.export(new ExportArtifactsCommand("slot-reputation"));
        assertThat(afterFirstReuse.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(readIdByMask(sliceCsv(afterFirstReuse.sliceName()))).containsExactly(
                Map.entry("d.example", "1"),
                Map.entry("c.example", "3"));

        changeActiveSet(asOf, List.of(), List.of("e.example"));
        var afterSecondReuse = service.export(new ExportArtifactsCommand("slot-reputation"));
        assertThat(afterSecondReuse.status()).isEqualTo(ExportRunStatus.COMPLETED);
        Path beforeReappearance = sliceCsv(afterSecondReuse.sliceName());
        assertThat(readIdByMask(beforeReappearance)).containsExactly(
                Map.entry("d.example", "1"),
                Map.entry("e.example", "2"),
                Map.entry("c.example", "3"));

        reincarnateWithSameProjection(asOf);
        var afterIdenticalReappearance = service.export(
                new ExportArtifactsCommand("slot-reputation"));
        assertThat(afterIdenticalReappearance.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(afterIdenticalReappearance.sliceName()).isNotEqualTo(afterSecondReuse.sliceName());
        assertThat(Files.readString(sliceCsv(afterIdenticalReappearance.sliceName())))
                .isEqualTo(Files.readString(beforeReappearance));

        assertThat(Files.readString(baselineCsv)).isEqualTo(immutableBaseline);
    }

    private ExportService exportService(ExportPlan plan) {
        return new ExportService(
                List.of(plan), revisionReader, progressStore, runLedger,
                snapshotReader, sliceWriter, recoveryService, operationGuard, clock);
    }

    private void writeMasks(ExportPlan plan, Map<String, String> masks) {
        var artifact = plan.artifacts().getFirst();
        List<ArtifactRow> rows = masks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    LinkedHashMap<String, String> values = new LinkedHashMap<>();
                    artifact.columns().forEach(column -> values.put(column, null));
                    values.put("id", entry.getKey());
                    values.put("mask", entry.getValue());
                    return ArtifactRow.ordered(values);
                })
                .toList();
        canonical.write("masks", new CanonicalArtifact("masks", artifact.columns(), rows));
    }

    private void initializeLifecycles(Instant asOf) throws Exception {
        try (var connection = dataframe.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE masks
                     SET _lifecycle_id = id + 100,
                         _first_confirmed_at_epoch_ms = ?,
                         _last_confirmed_at_epoch_ms = ?,
                         _valid_until_epoch_ms = CASE WHEN id <= 3 THEN ? ELSE ? END
                     """)) {
            statement.setLong(1, asOf.minusSeconds(1).toEpochMilli());
            statement.setLong(2, asOf.minusSeconds(1).toEpochMilli());
            statement.setLong(3, asOf.plusSeconds(3_600).toEpochMilli());
            statement.setLong(4, asOf.toEpochMilli());
            assertThat(statement.executeUpdate()).isEqualTo(5);
        }
    }

    private void activate(Instant asOf) {
        var disabled = lifecycleControl.load();
        var activating = disabled.beginActivation("fixed-export-slot-e2e-v1");
        assertThat(lifecycleControl.compareAndSet(disabled, activating)).isTrue();
        assertThat(lifecycleControl.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(asOf)))).isTrue();
    }

    private void changeActiveSet(Instant asOf,
                                 List<String> expire,
                                 List<String> activate) throws Exception {
        try (var connection = dataframe.getConnection()) {
            connection.setAutoCommit(false);
            try (var expiry = connection.prepareStatement("""
                    UPDATE masks SET _valid_until_epoch_ms = ? WHERE mask = ?
                    """)) {
                for (String mask : expire) {
                    expiry.setLong(1, asOf.toEpochMilli());
                    expiry.setString(2, mask);
                    expiry.addBatch();
                }
                expiry.executeBatch();
            }
            try (var activation = connection.prepareStatement("""
                    UPDATE masks
                    SET _lifecycle_id = _lifecycle_id + 1000,
                        _valid_until_epoch_ms = ?
                    WHERE mask = ?
                    """)) {
                for (String mask : activate) {
                    activation.setLong(1, asOf.plusSeconds(3_600).toEpochMilli());
                    activation.setString(2, mask);
                    activation.addBatch();
                }
                activation.executeBatch();
            }
            advanceExportSignals(connection);
            connection.commit();
        }
    }

    private void reincarnateWithSameProjection(Instant asOf) throws Exception {
        try (var connection = dataframe.getConnection()) {
            connection.setAutoCommit(false);
            try (var reincarnate = connection.prepareStatement("""
                    UPDATE masks
                    SET _lifecycle_id = CASE mask
                            WHEN 'd.example' THEN 5001
                            WHEN 'e.example' THEN 5002
                            WHEN 'c.example' THEN 5003
                        END,
                        _valid_until_epoch_ms = ?
                    WHERE mask IN ('c.example', 'd.example', 'e.example')
                    """)) {
                reincarnate.setLong(1, asOf.plusSeconds(3_600).toEpochMilli());
                assertThat(reincarnate.executeUpdate()).isEqualTo(3);
            }
            advanceExportSignals(connection);
            connection.commit();
        }
    }

    private void advanceExportSignals(java.sql.Connection connection) throws Exception {
        try (var revision = connection.prepareStatement("""
                UPDATE artifact_revision
                SET revision = revision + 1, changed_at = ?
                WHERE artifact = 'masks'
                """)) {
            revision.setString(1, clock.instant().toString());
            assertThat(revision.executeUpdate()).isOne();
        }
        try (var generation = connection.prepareStatement("""
                INSERT INTO artifact_projection_state(
                    artifact, required_generation, projected_generation, requested_at_ms)
                VALUES ('masks', 1, 0, ?)
                ON CONFLICT(artifact) DO UPDATE SET
                    required_generation = artifact_projection_state.required_generation + 1,
                    requested_at_ms = excluded.requested_at_ms
                """)) {
            generation.setLong(1, clock.instant().toEpochMilli());
            assertThat(generation.executeUpdate()).isOne();
        }
    }

    private Path sliceCsv(String sliceName) {
        return EXPORT_ROOT.resolve("slot-reputation")
                .resolve(sliceName)
                .resolve("masks_list_generated.csv");
    }

    private Map<String, String> readIdByMask(Path csv) throws Exception {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split(";", -1);
            result.put(csvValue(columns[1]), csvValue(columns[0]));
        }
        return result;
    }

    private String csvValue(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }
}
