package com.iocextractor.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.adapter.out.store.jdbc.*;
import com.iocextractor.application.artifact.*;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.lifecycle.*;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Opt-in G4 probe: generates rows incrementally and exercises real reduction, promotion and receipts. */
public final class DocumentWorkspaceCapacity {
    private static final List<String> HEADER = List.of("id", "value", "name", "source");
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private DocumentWorkspaceCapacity() { }

    public static void main(String[] arguments) throws Exception {
        int count = Integer.parseInt(arguments[0]);
        Path root = Path.of(arguments[1]);
        Files.createDirectories(root);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var definitions = List.of(new ArtifactIdentityDefinition("first", List.of("value"), false, 1),
                new ArtifactIdentityDefinition("last", List.of("value"), false, 1));
        var identities = new CanonicalArtifactIdentityResolver(definitions);
        var policies = Map.of("first", ArtifactWritePolicy.keepFirst(), "last",
                new ArtifactWritePolicy(ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, "name", Map.of()));
        var limits = new DocumentPreparationLimits(2 * 1024 * 1024, 64, 4096, 2048,
                2L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024, 128);
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("rows_per_artifact", count);
        metrics.put("limits", limits);
        metrics.put("lease_bytes", limits.leaseBytes());
        metrics.put("live_workspace_connections", 1);
        metrics.put("summed_workspace_cache_bytes", limits.cacheKiB() * 1024L);
        metrics.put("canonical_connection_cache_bytes", 2000L * 1024);
        metrics.put("confirmation_validation_cache_bytes", 64L * 1024);
        metrics.put("state_disk_budget_bytes", 8L * 1024 * 1024 * 1024);
        Path source = root.resolve("source.html");
        Files.writeString(source, "<p>Incrementally generated routed occurrences; no input list</p>");
        var schemas = definitions.stream().map(definition -> new DataframeArtifactSchema(definition.artifactName(),
                HEADER.stream().map(column -> new DataframeColumn(column, column.equals("id") ? "INTEGER" : "TEXT")).toList())).toList();
        try (var dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("capacity", "jdbc:sqlite:" + root.resolve("canonical.db"), "low-memory", 1, 1))) {
            new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
            new DataframeSchemaReconciler(dataSource).reconcile(schemas);
            var control = new JdbcLifecycleControlStore(dataSource, schemas);
            var disabled = control.load();
            var activating = disabled.beginActivation("capacity-fixed-1h");
            require(control.compareAndSet(disabled, activating), "activate");
            require(control.compareAndSet(activating, activating.completeActivation(EffectiveTime.at(NOW))), "activation complete");
            var observation = new ObservationId("capacity");
            var registration = new JdbcObservationRegistrationStore(dataSource, clock)
                    .registerNew(observation, ObservationOrigin.ONESHOT);
            var writer = new JdbcCanonicalLifecycleWriter(dataSource, schemas,
                    definitions.stream().map(definition -> new ArtifactIdAllocatorDefinition(definition.artifactName(),
                            ArtifactIdStrategy.ASCENDING, 1, 1)).toList(),
                    (LifecycleTimeSource) () -> EffectiveTime.at(NOW), new FixedRecordValidityPolicy(Duration.ofHours(1)), clock, definitions);
            var factory = new JdbcDocumentPreparationWorkspaceFactory(root.resolve("workspace"), limits, identities, "capacity-v1");
            long baselineHeap = liveHeap();
            metrics.put("baseline_live_heap_bytes", baselineHeap);
            metrics.put("baseline_rss_kib", currentRssKiB());
            var sampler = new ProcessingRouteComparison.PeakSampler();
            try (sampler; var workspace = factory.open(new ExtractionCommand("capacity", source, false, null, registration), policies)) {
                sampler.awaitFirstSample();
                long start = System.nanoTime();
                for (int index = 0; index < count; index++) {
                    require(workspace.firstOriginal("original-" + index), "original key");
                    for (String artifact : List.of("first", "last")) {
                        workspace.append(new RoutedArtifactCandidate(artifact, prepared(index, false)), true, false);
                        if (index % 10 == 0) {
                            workspace.append(new RoutedArtifactCandidate(artifact, prepared(index, true)), true, false);
                        }
                    }
                }
                var descriptors = definitions.stream().map(definition -> new ArtifactWritePlan(definition.artifactName(),
                        HEADER, List.<PreparedArtifactRow>of(), new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1))).toList();
                var plans = workspace.seal(descriptors, new DocumentPreparationSummary(count, count, DiagnosticSummary.empty()));
                metrics.put("prepare_seal_nanos", System.nanoTime() - start);
                metrics.put("sealed_live_heap_bytes", liveHeap());
                metrics.put("sealed_rss_kib", currentRssKiB());
                metrics.put("workspace_disk_bytes", bytes(root.resolve("workspace")));
                long validation = System.nanoTime();
                var signatures = new LinkedHashMap<String, String>();
                for (var plan : plans) { signatures.put(plan.artifactName(), verify(plan.rows(), plan.artifactName(), count)); }
                metrics.put("winner_validation_nanos", System.nanoTime() - validation);
                workspace.beginPromotion();
                long promotion = System.nanoTime();
                var receipt = new ConfirmationReceiptContext(new ConfirmationReceiptId("capacity-receipt"),
                        "capacity-v1", 2, Duration.ofDays(30));
                for (var plan : plans) {
                    var records = plan.rows().map(row -> new CanonicalRecordConfirmation(
                            identities.keyOf(plan.artifactName(), row.template()).orElseThrow(), row));
                    require(writer.confirm(new CanonicalArtifactConfirmation(observation, "capacity-source", receipt,
                            plan.artifactName(), HEADER, records, registration)).created() == count, "canonical rows");
                }
                metrics.put("promotion_nanos", System.nanoTime() - promotion);
                metrics.put("promoted_live_heap_bytes", liveHeap());
                metrics.put("promoted_rss_kib", currentRssKiB());
                long replay = System.nanoTime();
                var snapshot = new JdbcConfirmationReceiptStore(dataSource, schemas)
                        .findComplete("capacity-source", "capacity-v1", EffectiveTime.at(NOW)).orElseThrow();
                for (var artifact : snapshot.artifacts()) {
                    String actual = verify(artifact.records().map(CanonicalRecordConfirmation::preparedRow), artifact.artifactName(), count);
                    require(actual.equals(signatures.get(artifact.artifactName())), "receipt semantic signature");
                }
                metrics.put("receipt_read_nanos", System.nanoTime() - replay);
                metrics.put("receipt_live_heap_bytes", liveHeap());
                metrics.put("receipt_rss_kib", currentRssKiB());
                metrics.put("signatures", signatures);
                long disk = bytes(root);
                metrics.put("total_state_disk_bytes", disk);
                require(disk <= 8L * 1024 * 1024 * 1024, "state/output disk budget");
                require(dataSource.getHikariPoolMXBean().getActiveConnections() == 0, "all pooled cursors closed");
                workspace.discard();
            }
            metrics.put("peak_heap_bytes", sampler.peakHeapBytes());
            metrics.put("peak_rss_kib", sampler.peakCurrentRssKiB());
            require(bytes(root.resolve("workspace")) < 1024, "workspace cleanup");
        }
        System.out.println("CAPACITY_JSON=" + new ObjectMapper().writeValueAsString(metrics));
    }

    private static PreparedArtifactRow prepared(int index, boolean duplicate) {
        var values = new LinkedHashMap<String, String>();
        values.put("id", null);
        values.put("value", "host-" + index + ".example.test");
        values.put("name", (duplicate ? "updated-" : "original-") + index);
        values.put("source", duplicate ? "later-source" : "first-source");
        return new PreparedArtifactRow(ArtifactRow.ordered(values), Optional.of("id"),
                Map.of("name", new OccurrencePosition(index * 2L + (duplicate ? 1 : 0))));
    }

    private static String verify(RowSource<PreparedArtifactRow> rows, String artifact, int count) throws Exception {
        require(rows.size() == count, "declared count");
        var hash = MessageDigest.getInstance("SHA-256");
        try (var cursor = rows.open()) {
            int index = 0;
            while (cursor.next()) {
                require(index < count, "excess winner");
                boolean duplicate = artifact.equals("last") && index % 10 == 0;
                var expected = prepared(index++, duplicate);
                require(cursor.value().equals(expected), "whole winner and provenance at " + index);
                hash.update((expected.template().values() + ":" + expected.orderedFieldPositions() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            require(index == count, "actual count");
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static long liveHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long currentRssKiB() throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) { return Long.parseLong(line.split("\\s+")[1]); }
        }
        throw new IllegalStateException("Current RSS is unavailable");
    }

    private static long bytes(Path path) throws Exception {
        try (var files = Files.walk(path)) {
            long bytes = 0;
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (Files.isRegularFile(file)) { bytes += Files.size(file); }
            }
            return bytes;
        }
    }

    private static void require(boolean valid, String reason) {
        if (!valid) { throw new IllegalStateException("Capacity oracle failed: " + reason); }
    }
}
