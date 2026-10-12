package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Test-only bridge to the adapter-private candidate. Never packaged in the application. */
public final class StagedCapacityBundle implements AutoCloseable {
    /** Inputs resolved by the normal bootstrap catalogs, policies and resource configuration. */
    public record Inputs(DataSource dataSource, List<DataframeArtifactSchema> schemas,
            List<ArtifactIdAllocatorDefinition> ids, List<ArtifactIdentityDefinition> identities,
            JdbcLifecycleClock lifecycleClock, Clock clock, Duration ttl, JdbcWriterAdmission writers,
            JdbcDocumentPreparationWorkspaceFactory workspaces, Path imports,
            Map<String, ArtifactWritePolicy> policies, Duration receiptRetention,
            DiagnosticSink diagnostics, int snapshotReaders) { }

    private final JdbcStagedCanonicalBackend backend;
    private final JdbcStagedDatabaseLease lease;
    private final JdbcSnapshotSliceReader snapshots;
    private final Duration receiptRetention;
    private final Map<String, Long> phases = new java.util.TreeMap<>();
    private boolean measuring;
    private final boolean directProjection;

    /** Requires a freshly initialized, active native database before any intake starts. */
    public StagedCapacityBundle(Inputs inputs) throws Exception {
        this(inputs, false);
    }

    /** Explicit qualification-only choice; no production selector or alternate physical layout. */
    public StagedCapacityBundle(Inputs inputs, boolean directProjection) throws Exception {
        this(inputs, directProjection, false);
    }

    /** Compact journal is a separate isolated experiment, with persisted format and restart pins. */
    public StagedCapacityBundle(Inputs inputs, boolean directProjection, boolean compactJournal) throws Exception {
        this(inputs, directProjection, compactJournal, false);
    }

    /** Immutable ordinary receipts are qualification-only; mutable headers retain the shared protocol. */
    public StagedCapacityBundle(Inputs inputs, boolean directProjection, boolean compactJournal, boolean immutableReceipts) throws Exception {
        this.directProjection = directProjection;
        JdbcStagedDatabaseLease acquired;
        try (var c = inputs.dataSource().getConnection()) { acquired = JdbcStagedDatabaseLease.acquire(c); }
        try {
            backend = JdbcStagedCanonicalBackend.createEmpty(new JdbcStagedCanonicalConfiguration(
                    inputs.dataSource(), inputs.schemas(), inputs.ids(), inputs.identities(), inputs.lifecycleClock(),
                    inputs.clock(), new FixedRecordValidityPolicy(inputs.ttl()), inputs.writers(), inputs.workspaces(),
                    inputs.imports(), inputs.policies(), inputs.receiptRetention()), new JdbcPublicationObserver() {
                        public void observe(Phase phase, int attempt, int chunk) { StagedCapacityBundle.this.observe(phase, attempt, chunk); }
                        public void elapsed(Timing timing, long nanos) { StagedCapacityBundle.this.elapsed(timing, nanos); }
                    },
                    compactJournal ? JdbcTypedJournal.CHUNK_POINTER : JdbcTypedJournal.ROW_POINTER,
                    immutableReceipts ? JdbcReceiptPayloadLayout.IMMUTABLE : JdbcReceiptPayloadLayout.VERSIONED);
            snapshots = new JdbcSnapshotSliceReader(inputs.dataSource(), inputs.schemas(), inputs.clock(),
                    inputs.lifecycleClock(), inputs.diagnostics(), new DiagnosticFactory(inputs.clock()), inputs.writers(),
                    inputs.snapshotReaders(), new JdbcStagedSlotReconciliation(backend));
            lease = acquired;
            receiptRetention = inputs.receiptRetention();
        } catch (Exception | Error failure) {
            try { acquired.close(); } catch (Exception closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    /** Preserves Spring type discovery for both receipt cleanup and observation lookup. */
    public static Class<?> receiptType() { return JdbcStagedReceiptStore.class; }

    /** Complete set of mutable candidate ports, selected together by the qualification composition. */
    public Object port(String name) {
        return switch (name) {
            case "canonicalArtifactWriter" -> backend.confirmations();
            case "canonicalImportWriter" -> backend.imports();
            case "expiredArtifactStore" -> backend.expiry();
            case "artifactProjectionWorkStore" -> directProjection ? backend.directProjections() : backend.projections();
            case "lifecycleHistoryStore" -> backend.history();
            case "confirmationReceiptStore" -> backend.receipts(receiptRetention);
            case "dataframeImportCommitEvidenceStore" -> backend.importEvidence();
            case "snapshotSliceReader" -> snapshots;
            default -> throw new IllegalArgumentException("Unknown staged qualification port: " + name);
        };
    }

    private synchronized void observe(JdbcPublicationObserver.Phase phase, int attempt, int chunk) {
        if (measuring) {
            phases.merge(phase.name(), 1L, Long::sum);
            phases.merge("maximumAttempt", (long) attempt, Math::max);
        }
    }

    private synchronized void elapsed(JdbcPublicationObserver.Timing timing, long nanos) {
        if (measuring) {
            String prefix = "timing." + timing.name();
            phases.merge(prefix + ".count", 1L, Long::sum);
            phases.merge(prefix + ".totalNanos", nanos, Long::sum);
            phases.merge(prefix + ".maximumNanos", nanos, Math::max);
        }
    }

    /** Bounded unit/chunk/rebuild counters; no per-row sampling or durable business mutation. */
    public synchronized void beginWindow() { phases.clear(); measuring = true; }

    /** End the window only after the owning measurement worker has drained. */
    public synchronized Map<String, Long> endWindow() { measuring = false; return Map.copyOf(phases); }

    /** One durable cleanup page; its cost stays in the measured mixed workload. */
    public String maintenance() { return new JdbcPublicationMaintenance(backend).tick(128).toString(); }

    /** Deliberately outside the bulk window: complete census distinguishes eligible debt from protected rows. */
    public Map<String, Object> cleanupEvidence() throws java.sql.SQLException {
        var result = new java.util.LinkedHashMap<String, Object>();
        try (var connection = backend.configuration().dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                var families = new java.util.TreeMap<String, Object>();
                for (var table : backend.store().tables()) {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                            "SELECT COUNT(*),COALESCE(SUM(CASE WHEN " + JdbcPublicationVersionCleanup.eligible(backend.store(), table)
                                    + " THEN 1 ELSE 0 END),0) FROM " + JdbcSql.quote(table.versions())
                                    + " v JOIN publication_unit u ON u.id=v.unit_id")) {
                        if (!rows.next()) { throw new java.sql.SQLException("Missing cleanup census"); }
                        families.put(table.name(), Map.of("physicalRows", rows.getLong(1), "eligibleRows", rows.getLong(2),
                                "protectedRows", rows.getLong(1) - rows.getLong(2)));
                    }
                }
                var service = new java.util.TreeMap<String, Long>();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT family,position FROM publication_cleanup_cursor WHERE family LIKE '$service.%' ORDER BY family")) {
                    while (rows.next()) { service.put(rows.getString(1), rows.getLong(2)); }
                }
                result.put("families", families); result.put("committedService", service);
                result.put("scope", "Read-only snapshot census; protected rows are not deletion backlog; no steady-load assertion");
                connection.rollback();
            } catch (java.sql.SQLException | RuntimeException | Error failure) {
                JdbcLifecycleTransactions.rollback(connection, failure); throw failure;
            }
        }
        return result;
    }

    /** Read-only physical/control evidence through the pinned driver; never writes test state. */
    public Map<String, Object> projectionEvidence() throws java.sql.SQLException {
        var result = new java.util.LinkedHashMap<String, Object>();
        try (var connection = backend.configuration().dataSource().getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                for (String table : java.util.List.of("publication_unit", "publication_attempt", "publication_fact", "publication_chunk",
                        "artifact_projection_state", "publication_version_artifact_projection_state")) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + JdbcSql.quote(table))) {
                        if (!rows.next()) { throw new java.sql.SQLException("Missing physical count"); }
                        result.put(table, rows.getLong(1));
                    }
                }
                var plan = new java.util.ArrayList<String>();
                try (var rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT required_generation,projected_generation"
                        + " FROM artifact_projection_state WHERE artifact='masks'")) {
                    while (rows.next()) { plan.add(rows.getString(4)); }
                }
                result.put("committedProjectionReadPlan", plan);
                connection.rollback();
            } catch (java.sql.SQLException | RuntimeException | Error failure) {
                JdbcLifecycleTransactions.rollback(connection, failure); throw failure;
            }
        }
        return result;
    }

    /** The Spring context must stop dependent workers/readers before releasing the process lease. */
    @Override public void close() throws Exception {
        if (snapshots.activeReaders() != 0 || snapshots.queuedReaders() != 0
                || backend.configuration().admission().queuedWriters() != 0) {
            throw new IllegalStateException("Qualification workers/readers did not drain");
        }
        lease.close();
    }
}
