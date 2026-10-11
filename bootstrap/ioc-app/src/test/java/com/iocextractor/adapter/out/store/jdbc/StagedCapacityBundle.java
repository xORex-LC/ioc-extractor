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

    /** Requires a freshly initialized, active native database before any intake starts. */
    public StagedCapacityBundle(Inputs inputs) throws Exception {
        JdbcStagedDatabaseLease acquired;
        try (var c = inputs.dataSource().getConnection()) { acquired = JdbcStagedDatabaseLease.acquire(c); }
        try {
            backend = JdbcStagedCanonicalBackend.createEmpty(new JdbcStagedCanonicalConfiguration(
                    inputs.dataSource(), inputs.schemas(), inputs.ids(), inputs.identities(), inputs.lifecycleClock(),
                    inputs.clock(), new FixedRecordValidityPolicy(inputs.ttl()), inputs.writers(), inputs.workspaces(),
                    inputs.imports(), inputs.policies(), inputs.receiptRetention()), this::observe);
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
            case "artifactProjectionWorkStore" -> backend.projections();
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

    /** Bounded unit/chunk/rebuild counters; no per-row sampling or durable business mutation. */
    public synchronized void beginWindow() { phases.clear(); measuring = true; }

    /** End the window only after the owning measurement worker has drained. */
    public synchronized Map<String, Long> endWindow() { measuring = false; return Map.copyOf(phases); }

    /** One durable cleanup page; its cost stays in the measured mixed workload. */
    public String maintenance() { return new JdbcPublicationMaintenance(backend).tick(128).toString(); }

    /** The Spring context must stop dependent workers/readers before releasing the process lease. */
    @Override public void close() throws Exception {
        if (snapshots.activeReaders() != 0 || snapshots.queuedReaders() != 0
                || backend.configuration().admission().queuedWriters() != 0) {
            throw new IllegalStateException("Qualification workers/readers did not drain");
        }
        lease.close();
    }
}
