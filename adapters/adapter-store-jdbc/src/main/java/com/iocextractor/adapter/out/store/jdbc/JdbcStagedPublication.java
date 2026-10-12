package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.RecordValidityPolicy;
import com.iocextractor.common.IocExtractorException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native preparation outside admission, bounded hidden transfer and atomic safe-UTC publication. */
final class JdbcStagedPublication {
    record Environment(DataSource canonical, JdbcTypedPublicationStore store, JdbcLifecycleClock clock,
                       RecordValidityPolicy validity, Clock allocatorClock, JdbcWriterAdmission admission,
                       JdbcDocumentPreparationWorkspaceFactory workspaces, List<DataframeArtifactSchema> schemas) {
        Environment { schemas = List.copyOf(schemas); }
    }
    record Unit(String identity, String pins, Set<String> scopes, boolean promotion, JdbcWriterAdmission.OperationClass operation) {
        Unit(String identity, String pins, Set<String> scopes, boolean promotion) {
            this(identity, pins, scopes, promotion,
                    promotion ? JdbcWriterAdmission.OperationClass.PROMOTION : JdbcWriterAdmission.OperationClass.CONTROL);
        }
        Unit {
            scopes = Set.copyOf(scopes);
            if (identity.isBlank() || pins.isBlank() || scopes.isEmpty() || scopes.size() > 256) {
                throw new IllegalArgumentException("Publication requires bounded identity, pins and dependencies");
            }
        }
    }
    private final Environment environment;
    private final JdbcPublicationTransactions transactions;
    private final JdbcPublicationProtocol protocol = new JdbcPublicationProtocol();
    private final JdbcPublicationOwner owners = new JdbcPublicationOwner();
    private final JdbcPublicationObserver observer;
    private final java.util.concurrent.locks.ReentrantLock controlPreparation = new java.util.concurrent.locks.ReentrantLock(true);

    JdbcStagedPublication(Environment environment, JdbcPublicationObserver observer) {
        this.environment = environment;
        this.observer = observer;
        transactions = new JdbcPublicationTransactions(environment.canonical(), environment.admission(), observer);
    }

    boolean execute(Unit unit, NativeTask task) {
        if (unit.promotion()) { return executeAdmitted(unit, task); }
        return admitControl(() -> executeAdmitted(unit, task));
    }

    private boolean admitControl(java.util.function.BooleanSupplier work) {
        if (controlPreparation.isHeldByCurrentThread() || environment.admission().ownedByCurrentThread()) {
            throw new IocExtractorException("Control preparation cannot wait under canonical ownership or recurse");
        }
        long waiting = System.nanoTime();
        try { controlPreparation.lockInterruptibly(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Control preparation admission interrupted", failure);
        }
        long held = System.nanoTime();
        try {
            observer.elapsed(JdbcPublicationObserver.Timing.CONTROL_GATE_WAIT, held - waiting);
            return work.getAsBoolean();
        }
        finally {
            controlPreparation.unlock();
            observer.elapsed(JdbcPublicationObserver.Timing.CONTROL_GATE_HOLD, System.nanoTime() - held);
        }
    }

    /** Same typed protocol in one transaction, allowlisted to one projection metadata row. */
    boolean updateProjection(Unit unit, String artifact,
                             java.util.function.Predicate<JdbcProjectionMutation> mutation) {
        if (unit.promotion() || !unit.scopes().equals(Set.of("artifact:" + artifact))
                || !environment.store().artifacts().contains(artifact)) {
            throw new IllegalArgumentException("Direct execution only admits one configured projection artifact");
        }
        // Direct state is connection-local and needs no private-control workspace.
        // Keep one writer owner; prepared controls revalidate the advanced epochs.
        if (controlPreparation.isHeldByCurrentThread() || environment.admission().ownedByCurrentThread()) {
            throw new IocExtractorException("Direct projection control cannot recurse under canonical/preparation ownership");
        }
        try {
            boolean result = transactions.execute(operation(unit), c -> {
                JdbcLifecycleTransactions.acquireActiveWriteOwnership(c);
                // No-op controls still obey the canonical clock health contract.
                environment.clock().now(c);
                Map<String, Long> observed = dependencies(c, unit.scopes());
                try (var state = new JdbcDirectProjectionState(c, environment.store(), artifact,
                        environment.workspaces().canonicalChunkBytes())) {
                    boolean applied = mutation.test(state.mutation());
                    var chunk = state.effects(environment.store(), environment.workspaces().canonicalChunkBytes());
                    if (!chunk.isEmpty()) {
                        var manifest = new JdbcPublicationProtocol.Manifest(1, chunk.size(), Long.MAX_VALUE, observed);
                        var attempt = protocol.begin(c, unit.identity(), unit.pins(), manifest);
                        observer.observe(JdbcPublicationObserver.Phase.BEFORE_HIDDEN_CHUNK, 1, 0);
                        chunk.append(c, protocol, attempt, "artifact:" + artifact, 0, environment.store().journal(), environment.store().receiptPayloads());
                        observer.observe(JdbcPublicationObserver.Phase.HIDDEN_CHUNK, 1, 0);
                        protocol.seal(c, attempt);
                        observer.observe(JdbcPublicationObserver.Phase.SEALED, 1, 0);
                        observer.observe(JdbcPublicationObserver.Phase.BEFORE_PUBLICATION, 1, 0);
                        publish(c, unit, null, attempt, 1);
                    } else {
                        observer.observe(JdbcPublicationObserver.Phase.BEFORE_PUBLICATION, 1, 0);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new SQLException("Canonical publication interrupted before commit");
                    }
                    return applied;
                }
            });
            observer.observe(JdbcPublicationObserver.Phase.COMMITTED, 1, 0);
            return result;
        } catch (SQLException failure) {
            failControl(unit, failure);
            throw new IocExtractorException("Direct projection publication failed", failure);
        } catch (RuntimeException | Error failure) {
            failControl(unit, failure);
            throw failure;
        }
    }

    private boolean executeAdmitted(Unit unit, NativeTask task) {
        JdbcPublicationOwner.Owner owner = null;
        try {
            if (committed(unit)) { return false; }
            if (unit.promotion()) { owner = acquire(unit); }
            if (committed(unit)) {
                if (owner != null) {
                    var acquired = owner;
                    transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> { owners.release(c, acquired); return null; });
                }
                return false;
            }
            prepareAttempts(unit, task, owner);
            return true;
        } catch (SQLException failure) {
            if (owner != null) { failOwner(owner, failure); }
            else if (!unit.promotion()) { failControl(unit, failure); }
            throw new IocExtractorException("Staged canonical publication failed", failure);
        } catch (RuntimeException | Error failure) {
            if (owner != null) { failOwner(owner, failure); }
            else if (!unit.promotion()) { failControl(unit, failure); }
            throw failure;
        }
    }

    private void prepareAttempts(Unit unit, NativeTask task, JdbcPublicationOwner.Owner owner) throws SQLException {
        JdbcPublicationConflict last = null;
        for (int number = 1; number <= 3; number++) {
            if (owner != null) {
                transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> owners.nextAttempt(c, owner));
            }
            try { attempt(unit, task, owner, number); return; }
            catch (JdbcPublicationConflict conflict) {
                last = conflict;
                observer.observe(JdbcPublicationObserver.Phase.REBUILD, number, 0);
            }
        }
        throw new SQLException("Publication rebuild budget exhausted", last);
    }

    boolean committed(Unit unit) throws SQLException {
        String sql = "SELECT pins,state FROM publication_unit WHERE identity=?";
        if (environment.store().retiredIdentities()) {
            sql += " UNION ALL SELECT pins,'COMMITTED' FROM publication_retired_identity WHERE identity=?";
        }
        try (var connection = environment.canonical().getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, unit.identity());
            if (environment.store().retiredIdentities()) { statement.setString(2, unit.identity()); }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { return false; }
                if (!unit.pins().equals(rows.getString(1))) { throw new SQLException("Canonical publication pins changed"); }
                boolean result = rows.getString(2).equals("COMMITTED");
                if (rows.next()) { throw new SQLException("Duplicate live and retired publication identity"); }
                return result;
            }
        }
    }

    private JdbcPublicationOwner.Owner acquire(Unit unit) throws SQLException {
        return transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
            owners.releaseCommitted(c);
            owners.recordReclaim(c, unit.identity(), environment.clock().now(c).value().toEpochMilli());
            return owners.acquire(c, unit.identity(), owners.fence(c));
        });
    }

    private void failOwner(JdbcPublicationOwner.Owner owner, Throwable failure) {
        cleanup(failure, c -> {
            owners.require(c, owner);
            cancelHidden(c, owner.unit());
            // Operational failure time is not confirmation authority. Cleanup must
            // remain available when the safe lifecycle clock itself rejected publication.
            owners.fail(c, owner, environment.allocatorClock().millis(), "PUBLICATION.RETRYABLE_FAILURE");
            return null;
        });
    }

    private void failControl(Unit unit, Throwable failure) {
        cleanup(failure, c -> { cancelHidden(c, unit.identity()); return null; });
    }

    /** Cancellation must not cancel its own durable rollback/release; preserve the caller's flag. */
    private void cleanup(Throwable failure, JdbcPublicationTransactions.Work<Void> work) {
        boolean interrupted = Thread.interrupted();
        try {
            transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, work);
        } catch (SQLException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private void cancelHidden(Connection connection, String identity) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT u.id,u.fence,a.stamp FROM publication_unit u"
                + " JOIN publication_attempt a ON a.unit_id=u.id AND a.fence=u.fence WHERE u.identity=? AND u.state IN('PENDING','SEALED')")) {
            statement.setString(1, identity);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) { protocol.cancel(connection, new JdbcPublicationProtocol.Attempt(rows.getLong(1), rows.getLong(2), rows.getLong(3))); }
            }
        }
    }

    private void attempt(Unit unit, NativeTask task, JdbcPublicationOwner.Owner owner, int number) throws SQLException {
        EffectiveTime preparedAt = environment.clock().now();
        String workspace = unit.identity() + ":" + (owner == null ? java.util.UUID.randomUUID() : owner.fence()) + ":" + number;
        try (var privateState = JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.WORKSPACE_ADMISSION,
                () -> JdbcPrivatePublicationWorkspace.create(environment.workspaces(), workspace, unit.pins(), unit.promotion()))) {
            JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.SCHEMA,
                    () -> { initializeNative(privateState); return null; });
            Prepared prepared = JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.HYDRATION,
                    () -> hydrate(unit, task, privateState, preparedAt));
            var authority = new JdbcCanonicalReservationAuthority(environment.canonical(), environment.allocatorClock(), environment.admission());
            var context = new JdbcCanonicalPreparationContext(authority,
                    c -> JdbcPrivateMutationJournal.open(c, unit.identity(), unit.pins()), prepared.slots(), privateState.limits().cacheKiB());
            JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.NATIVE_PREPARATION,
                    () -> { task.prepare(privateState.dataSource(), context, preparedAt); return null; });
            privateState.seal(unit.pins());
            observer.observe(JdbcPublicationObserver.Phase.PREPARED, number, 0);
            try (var source = privateState.dataSource().getConnection()) {
                source.setAutoCommit(false);
                try {
                var transfer = new JdbcPublicationTransfer(environment.store(), JdbcCanonicalWorkspaceLimits.chunkBytes(privateState.limits()));
                var description = JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.DESCRIPTION,
                        () -> transfer.describe(source, unit.promotion(), preparedAt.value().toEpochMilli(), prepared.deadline()));
                var writer = new TransferWriter(unit, owner, number);
                var attempt = JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.TRANSFER,
                        () -> transfer.stage(source, unit.identity(), unit.pins(), prepared.dependencies(), description, unit.promotion(), writer));
                observer.observe(JdbcPublicationObserver.Phase.BEFORE_PUBLICATION, number, 0);
                publish(unit, owner, attempt, number);
                source.rollback();
                } catch (SQLException | RuntimeException | Error failure) {
                    JdbcLifecycleTransactions.rollback(source, failure); throw failure;
                }
            }
            observer.observe(JdbcPublicationObserver.Phase.COMMITTED, number, 0);
        }
    }

    private void initializeNative(JdbcPrivatePublicationWorkspace privateState) {
        new SqliteUserVersionSchemaMigrator(privateState.dataSource(), DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(privateState.dataSource()).reconcile(environment.schemas());
        privateState.checkQuota();
    }

    private Prepared hydrate(Unit unit, NativeTask task, JdbcPrivatePublicationWorkspace privateState, EffectiveTime asOf)
            throws SQLException {
        try (var base = environment.canonical().getConnection();
             var ignored = new JdbcPublicationReadLimits(base, privateState.limits());
             var working = privateState.dataSource().getConnection()) {
            base.setAutoCommit(false); working.setAutoCommit(false);
            try {
            Map<String, Long> dependencies = dependencies(base, unit.scopes());
            var hydrator = new JdbcPublicationHydrator(base, working, environment.store().tables(), privateState::checkQuota, privateState.limits());
            var selected = new JdbcSelectedCanonicalBase(base, working, hydrator);
            selected.authorityMetadata();
            var slots = new JdbcPublicationSlotBase(base, working, privateState::checkQuota);
            long deadline;
            try (var selection = new JdbcPublicationSelection(base)) {
                task.hydrate(selection, selected, slots, asOf);
                deadline = unit.promotion() ? Math.min(slots.minimumDeadline(),
                        selection.minimumDeadline(environment.store().artifacts(), asOf.value().toEpochMilli())) : Long.MAX_VALUE;
            }
            JdbcPrivateChangeCapture.install(working, environment.store().tables());
            working.commit(); base.rollback();
            return new Prepared(dependencies, slots.registry(), deadline);
            } catch (SQLException | RuntimeException | Error failure) {
                JdbcLifecycleTransactions.rollback(working, failure);
                JdbcLifecycleTransactions.rollback(base, failure);
                throw failure;
            }
        }
    }

    private static Map<String, Long> dependencies(Connection base, Set<String> scopes) throws SQLException {
        var result = new LinkedHashMap<String, Long>();
        try (var statement = base.prepareStatement("SELECT version FROM publication_epoch WHERE scope=?")) {
            for (String scope : scopes.stream().sorted().toList()) {
                statement.setString(1, scope);
                try (var rows = statement.executeQuery()) { result.put(scope, rows.next() ? rows.getLong(1) : 0L); }
            }
        }
        return Map.copyOf(result);
    }

    private void publish(Unit unit, JdbcPublicationOwner.Owner owner, JdbcPublicationProtocol.Attempt attempt, int number) throws SQLException {
        transactions.execute(operation(unit), c -> {
            publish(c, unit, owner, attempt, number);
            return null;
        });
    }

    private void publish(Connection connection, Unit unit, JdbcPublicationOwner.Owner owner,
                         JdbcPublicationProtocol.Attempt attempt, int number) throws SQLException {
        if (owner != null) { owners.require(connection, owner); }
        JdbcLifecycleTransactions.acquireActiveWriteOwnership(connection);
        long utc = environment.clock().now(connection).value().toEpochMilli();
        protocol.publish(connection, attempt, utc, unit.pins(), environment.validity());
        observer.observe(JdbcPublicationObserver.Phase.PUBLICATION_STAGED, number, 0);
        if (owner != null) { owners.release(connection, owner); }
        if (Thread.currentThread().isInterrupted()) { throw new SQLException("Canonical publication interrupted before commit"); }
    }

    private static JdbcWriterAdmission.OperationClass operation(Unit unit) {
        return unit.operation();
    }

    private record Prepared(Map<String, Long> dependencies, JdbcExportSlotRegistry slots, long deadline) { }
    interface NativeTask {
        void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base, JdbcPublicationSlotBase slots, EffectiveTime asOf)
                throws SQLException;
        void prepare(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) throws SQLException;
    }

    private final class TransferWriter implements JdbcPublicationTransfer.Writer {
        private final Unit unit;
        private final JdbcPublicationOwner.Owner owner;
        private final int number;
        TransferWriter(Unit unit, JdbcPublicationOwner.Owner owner, int number) {
            this.unit = unit; this.owner = owner; this.number = number;
        }
        public JdbcPublicationProtocol.Attempt begin(String identity, String pins, JdbcPublicationProtocol.Manifest manifest)
                throws SQLException {
            return transactions.execute(operation(unit), c -> {
                if (owner != null) { owners.require(c, owner); }
                try (var statement = c.prepareStatement("SELECT u.id,u.fence,a.stamp FROM publication_unit u"
                        + " JOIN publication_attempt a ON a.unit_id=u.id"
                        + " AND a.fence=u.fence WHERE u.identity=? AND u.state IN('PENDING','SEALED','CANCELLED')")) {
                    statement.setString(1, identity);
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) {
                            return protocol.rebuild(c, new JdbcPublicationProtocol.Attempt(rows.getLong(1), rows.getLong(2), rows.getLong(3)), pins, manifest);
                        }
                    }
                }
                return protocol.begin(c, identity, pins, manifest);
            });
        }
        public void append(JdbcPublicationProtocol.Attempt attempt, String scope, int ordinal, JdbcTypedPublicationChunk chunk)
                throws SQLException {
            observer.observe(JdbcPublicationObserver.Phase.BEFORE_HIDDEN_CHUNK, number, ordinal);
            transactions.execute(operation(unit), c -> {
                if (owner != null) { owners.require(c, owner); }
                chunk.append(c, protocol, attempt, scope, ordinal, environment.store().journal(), environment.store().receiptPayloads()); return null;
            });
            observer.observe(JdbcPublicationObserver.Phase.HIDDEN_CHUNK, number, ordinal);
        }
        public void seal(JdbcPublicationProtocol.Attempt attempt) throws SQLException {
            transactions.execute(operation(unit), c -> {
                if (owner != null) { owners.require(c, owner); }
                protocol.seal(c, attempt); return null;
            });
            observer.observe(JdbcPublicationObserver.Phase.SEALED, number, 0);
        }
    }
}
