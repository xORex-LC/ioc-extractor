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

    JdbcStagedPublication(Environment environment, JdbcPublicationObserver observer) {
        this.environment = environment;
        this.observer = observer;
        transactions = new JdbcPublicationTransactions(environment.canonical(), environment.admission());
    }

    boolean execute(Unit unit, NativeTask task) {
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
        try (var connection = environment.canonical().getConnection();
             var statement = connection.prepareStatement("SELECT pins,state FROM publication_unit WHERE identity=?")) {
            statement.setString(1, unit.identity());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { return false; }
                if (!unit.pins().equals(rows.getString(1))) { throw new SQLException("Canonical publication pins changed"); }
                return rows.getString(2).equals("COMMITTED");
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
        try {
            transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
                owners.require(c, owner);
                cancelHidden(c, owner.unit());
                owners.fail(c, owner, environment.clock().now(c).value().toEpochMilli(), "PUBLICATION.RETRYABLE_FAILURE");
                return null;
            });
        } catch (SQLException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    private void failControl(Unit unit, Throwable failure) {
        try {
            transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> { cancelHidden(c, unit.identity()); return null; });
        } catch (SQLException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
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
        try (var privateState = JdbcPrivatePublicationWorkspace.create(environment.workspaces(), workspace, unit.pins())) {
            initializeNative(privateState);
            Prepared prepared = hydrate(unit, task, privateState, preparedAt);
            var authority = new JdbcCanonicalReservationAuthority(environment.canonical(), environment.allocatorClock(), environment.admission());
            var context = new JdbcCanonicalPreparationContext(authority,
                    c -> JdbcPrivateMutationJournal.open(c, unit.identity(), unit.pins()), prepared.slots(), privateState.limits().cacheKiB());
            task.prepare(privateState.dataSource(), context, preparedAt);
            privateState.seal(unit.pins());
            observer.observe(JdbcPublicationObserver.Phase.PREPARED, number, 0);
            try (var source = privateState.dataSource().getConnection()) {
                source.setAutoCommit(false);
                try {
                var transfer = new JdbcPublicationTransfer(environment.store());
                var description = transfer.describe(source, unit.promotion(), preparedAt.value().toEpochMilli(), prepared.deadline());
                var writer = new TransferWriter(unit, owner, number);
                var attempt = transfer.stage(source, unit.identity(), unit.pins(), prepared.dependencies(), description, unit.promotion(), writer);
                observer.observe(JdbcPublicationObserver.Phase.BEFORE_PUBLICATION, number, 0);
                publish(unit, owner, attempt);
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

    private void publish(Unit unit, JdbcPublicationOwner.Owner owner, JdbcPublicationProtocol.Attempt attempt) throws SQLException {
        transactions.execute(operation(unit), c -> {
            if (owner != null) { owners.require(c, owner); }
            JdbcLifecycleTransactions.acquireActiveWriteOwnership(c);
            long utc = environment.clock().now(c).value().toEpochMilli();
            protocol.publish(c, attempt, utc, unit.pins(), environment.validity());
            if (owner != null) { owners.release(c, owner); }
            return null;
        });
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
            transactions.execute(operation(unit), c -> {
                if (owner != null) { owners.require(c, owner); }
                chunk.append(c, protocol, attempt, scope, ordinal); return null;
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
