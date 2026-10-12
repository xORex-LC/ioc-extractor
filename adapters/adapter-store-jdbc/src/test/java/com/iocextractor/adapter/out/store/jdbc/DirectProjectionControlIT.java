package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.tck.junit.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One-row controls share CAS, visibility, epochs and crash boundaries with prepared publications. */
@IntegrationTest
@Timeout(45)
class DirectProjectionControlIT {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesGenerationCasAndErrorAssociation(boolean direct) throws Exception {
        try (var fixture = fixture(JdbcPublicationObserver.NOOP)) {
            fixture.seed();
            var store = store(fixture, direct);
            var one = store.load("masks").requiredGeneration();
            assertThat(one.value()).isEqualTo(1);
            assertThat(store.recordFailure("masks", one, "PROJECTION.TEST")).isTrue();
            assertThat(error(fixture)).isEqualTo("PROJECTION.TEST");
            assertThat(store.acknowledge(ack(2))).isFalse();
            assertThat(store.acknowledge(ack(1))).isTrue();
            assertThat(error(fixture)).isNull();
            assertThatThrownBy(() -> ack(0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.recordFailure("masks", one, "PROJECTION.STALE")).isFalse();
            long epoch = epoch(fixture);
            assertThat(store.acknowledge(ack(1))).isTrue();
            assertThat(epoch(fixture)).as("unchanged timestamp/value creates no epoch effect").isEqualTo(epoch);
            fixture.backend.confirmations().confirm(fixture.document("second", "next.example", "feed"));
            assertThat(store.load("masks").requiredGeneration().value()).isEqualTo(2);
            assertThat(store.recordFailure("masks", one, "PROJECTION.STALE")).isFalse();
            assertThat(store.acknowledge(ack(1))).isTrue();
            assertThat(store.load("masks").pending()).isTrue();
            assertThat(store.recordFailure("masks", new ProjectionGeneration(2), "PROJECTION.CURRENT")).isTrue();
            epoch = epoch(fixture);
            assertThat(store.recordFailure("masks", new ProjectionGeneration(2), "PROJECTION.CURRENT")).isTrue();
            assertThat(epoch(fixture)).isEqualTo(epoch);
            assertThat(store.acknowledge(ack(2))).isTrue();
            assertThat(store.acknowledge(ack(1))).isFalse();
            assertThat(store.load("masks").pending()).isFalse();
            assertThat(error(fixture)).isNull();
            assertThatThrownBy(() -> store.load("unknown")).isInstanceOf(IllegalArgumentException.class);
            assertTemporaryStateAbsent(fixture);
        }
    }

    @ParameterizedTest
    @CsvSource({"masks,true,1", "masks,false,0", "hashes,true,0"})
    void preparedPromotionRevalidatesConcurrentControls(String artifact, boolean effective, int expectedRebuilds) throws Exception {
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        var rebuilds = new AtomicInteger();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.PREPARED && armed.getAndSet(false)) {
                paused.countDown(); await(resume);
            }
        }); var workers = Executors.newSingleThreadExecutor()) {
            fixture.seed();
            var controls = fixture.backend.directProjections();
            if (artifact.equals("hashes")) { fixture.stageAndRegister(); fixture.promoteHead(); }
            if (!effective) { assertThat(controls.acknowledge(ack(1))).isTrue(); }
            armed.set(true);
            var publication = workers.submit(() -> fixture.backend.confirmations().confirm(
                    fixture.document("concurrent", "other.example", "feed")));
            try {
                assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(controls.acknowledge(new ProjectionAcknowledgement(artifact,
                        controls.load(artifact).requiredGeneration()))).isTrue();
                resume.countDown();
                publication.get(20, TimeUnit.SECONDS);
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
            assertThat(rebuilds.get()).isEqualTo(expectedRebuilds);
            assertThat(controls.load("masks").projectedGeneration().value()).isEqualTo(artifact.equals("masks") ? 1 : 0);
            assertThat(controls.load("masks").requiredGeneration().value()).isEqualTo(artifact.equals("hashes") ? 3 : 2);
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(artifact.equals("hashes") ? StagedPublicationFixture.ROWS + 2 : 2);
            assertThat(fixture.number("SELECT COUNT(*) FROM confirmation_receipt")).isEqualTo(2);
        } finally { resume.countDown(); }
    }

    @Test
    void directAckBypassesPausedPreparedControlAndForcesRevalidation() throws Exception {
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        var rebuilds = new AtomicInteger();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.PREPARED && armed.getAndSet(false)) {
                paused.countDown(); await(resume);
            }
        }); var workers = Executors.newFixedThreadPool(2)) {
            fixture.seed();
            armed.set(true);
            var maintenance = workers.submit(() -> new JdbcStagedControl(fixture.backend).execute(
                    Set.of("artifact:masks"), JdbcWriterAdmission.OperationClass.MAINTENANCE,
                    (selection, base, slots) -> base.metadata("masks"),
                    (working, context, asOf) -> new JdbcArtifactProjectionWorkStore(
                            working, fixture.backend.configuration().allocatorClock())
                            .recordFailure("masks", new ProjectionGeneration(1), "PREPARED.FAILURE")));
            try {
                assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
                var acknowledgement = workers.submit(() -> fixture.backend.directProjections().acknowledge(ack(1)));
                assertThat(acknowledgement.get(5, TimeUnit.SECONDS))
                        .as("direct ACK needs no second private control workspace").isTrue();
                resume.countDown();
                assertThat(maintenance.get(20, TimeUnit.SECONDS)).isFalse();
                assertThat(rebuilds.get()).isOne();
                assertThat(error(fixture)).isNull();
                assertThat(fixture.backend.directProjections().load("masks").projectedGeneration().value()).isEqualTo(1);
            } finally {
                resume.countDown(); workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally { resume.countDown(); }
    }

    @Test
    void directWriterOwnershipExcludesAConcurrentPublicationUntilCommit() throws Exception {
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.PUBLICATION_STAGED && armed.getAndSet(false)) {
                paused.countDown(); await(resume);
            }
        }); var workers = Executors.newFixedThreadPool(2)) {
            fixture.seed(); armed.set(true);
            var acknowledgement = workers.submit(() -> fixture.backend.directProjections().acknowledge(ack(1)));
            try {
                assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
                var started = new CountDownLatch(1);
                var publication = workers.submit(() -> {
                    started.countDown();
                    return fixture.backend.confirmations().confirm(fixture.document("after-ack", "next.example", "feed"));
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> publication.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                resume.countDown();
                assertThat(acknowledgement.get(15, TimeUnit.SECONDS)).isTrue();
                publication.get(20, TimeUnit.SECONDS);
                assertThat(fixture.backend.directProjections().load("masks").projectedGeneration().value()).isEqualTo(1);
                assertThat(fixture.backend.directProjections().load("masks").requiredGeneration().value()).isEqualTo(2);
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        } finally { resume.countDown(); }
    }

    @Test
    void importRebuildPreservesAckAndRecoversFromReceiptWithoutStage() throws Exception {
        var paused = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean(); var rebuilds = new AtomicInteger();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.PREPARED && armed.getAndSet(false)) {
                paused.countDown(); await(resume);
            }
        }); var workers = Executors.newSingleThreadExecutor()) {
            fixture.seed();
            var command = fixture.stageAndRegister();
            armed.set(true);
            var promotion = workers.submit(fixture::promoteHead);
            try {
                assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.backend.directProjections().acknowledge(ack(1))).isTrue();
                resume.countDown(); promotion.get(20, TimeUnit.SECONDS);
                assertThat(rebuilds.get()).isOne();
                assertThat(fixture.backend.directProjections().load("masks").projectedGeneration().value()).isEqualTo(1);
                assertThat(fixture.backend.directProjections().load("masks").requiredGeneration().value()).isEqualTo(2);
                assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(StagedPublicationFixture.ROWS + 1);
                assertThat(fixture.number("SELECT COUNT(*) FROM hashes")).isEqualTo(StagedPublicationFixture.ROWS);
                Files.delete(new ImportWorkspaceLayout(fixture.root.resolve("stage")).paths(StagedPublicationFixture.DELIVERY).sealed());
                long units = fixture.number("SELECT COUNT(*) FROM publication_unit");
                var recovered = fixture.backend.imports().promote(command);
                assertThat(recovered.outcome()).isEqualTo(com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome.ALREADY_COMMITTED);
                assertThat(recovered.acceptedRows()).as("logical import rows, each supplying two artifacts")
                        .isEqualTo(StagedPublicationFixture.ROWS);
                assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit")).isEqualTo(units);
            } finally { resume.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        } finally { resume.countDown(); }
    }

    @Test
    void rejectsUnadmittedScopeAndInvalidCommandsWithoutProtocolWrites() throws Exception {
        try (var fixture = fixture(JdbcPublicationObserver.NOOP)) {
            fixture.seed();
            var controls = fixture.backend.directProjections();
            assertThatThrownBy(() -> controls.recordFailure("masks", new ProjectionGeneration(1), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controls.recordFailure("masks", new ProjectionGeneration(1), " "))
                    .isInstanceOf(IllegalArgumentException.class);
            for (var scopes : java.util.List.of(Set.of("artifact:hashes"), Set.of("artifact:unknown"))) {
                String artifact = scopes.contains("artifact:unknown") ? "unknown" : "masks";
                assertThatThrownBy(() -> fixture.backend.publication().updateProjection(
                        new JdbcStagedPublication.Unit("invalid", "pins", scopes, false), artifact, mutation -> true))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> new JdbcProjectionMutation(org.springframework.jdbc.core.simple.JdbcClient.create(fixture.dataframe),
                    "unadmitted_table")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.backend.configuration().admission().execute(
                    JdbcWriterAdmission.OperationClass.CONTROL, () -> controls.acknowledge(ack(1))))
                    .isInstanceOf(com.iocextractor.common.IocExtractorException.class).hasMessageContaining("ownership");
            assertThatThrownBy(() -> fixture.backend.publication().updateProjection(
                    new JdbcStagedPublication.Unit("recursive", "pins", Set.of("artifact:masks"), false), "masks",
                    mutation -> controls.acknowledge(ack(1)))).hasMessageContaining("recurse");
        }
    }

    @Test
    void noOpInterruptionCannotBeReportedAsSuccessfulCommit() throws Exception {
        var armed = new AtomicBoolean();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.BEFORE_PUBLICATION && armed.getAndSet(false)) {
                Thread.currentThread().interrupt();
            }
        })) {
            fixture.seed();
            var controls = fixture.backend.directProjections();
            assertThat(controls.acknowledge(ack(1))).isTrue();
            long units = fixture.number("SELECT COUNT(*) FROM publication_unit");
            armed.set(true);
            try {
                assertThatThrownBy(() -> controls.acknowledge(ack(1))).hasMessageContaining("Direct projection");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit")).isEqualTo(units);
            assertThat(controls.load("masks").projectedGeneration().value()).isEqualTo(1);
            assertTemporaryStateAbsent(fixture);
        }
    }

    @Test
    void unsupportedCatalogShapeFailsClosedAndDisposesTemporaryState() throws Exception {
        try (var fixture = fixture(JdbcPublicationObserver.NOOP); var connection = fixture.dataframe.getConnection()) {
            fixture.seed();
            var original = fixture.backend.store().tables().stream().filter(table -> table.name().equals("artifact_projection_state"))
                    .findFirst().orElseThrow();
            var columns = new java.util.ArrayList<>(original.columns()); columns.add("unsupported_future_column");
            var invalid = new JdbcTypedPublicationStore(java.util.List.of(new JdbcPublicationTable(original.name(), columns,
                    original.keys(), original.nativeDdl())), Set.of("masks", "hashes"));
            assertThatThrownBy(() -> new JdbcDirectProjectionState(connection, invalid, "masks", 65536))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_temp_master WHERE name='direct_projection_state'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    @Test
    void jdbcCloseFailurePreservesTheOriginalErrorAndDisposesOwnedTemporaryState() throws Exception {
        try (var fixture = fixture(JdbcPublicationObserver.NOOP); var connection = fixture.dataframe.getConnection()) {
            fixture.seed();
            var borrowed = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> {
                        Object result = invoke(connection, method, arguments);
                        return method.getName().equals("prepareStatement") && ((String) arguments[0]).contains("FROM temp.direct_projection_state")
                                ? failingCloseStatement((java.sql.PreparedStatement) result) : result;
                    });
            assertThatThrownBy(() -> new JdbcDirectProjectionState(borrowed, fixture.backend.store(), "masks", 65536))
                    .isInstanceOf(java.sql.SQLException.class).hasMessage("injected result-set close failure")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).extracting(Throwable::getMessage)
                            .contains("injected statement close failure"));
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_temp_master WHERE name='direct_projection_state'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private static java.sql.PreparedStatement failingCloseStatement(java.sql.PreparedStatement delegate) {
        var armed = new AtomicBoolean();
        return (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(DirectProjectionControlIT.class.getClassLoader(),
                new Class<?>[]{java.sql.PreparedStatement.class}, (proxy, method, arguments) -> {
                    Object result = invoke(delegate, method, arguments);
                    if (method.getName().equals("executeQuery")) {
                        armed.set(true);
                        return java.lang.reflect.Proxy.newProxyInstance(DirectProjectionControlIT.class.getClassLoader(),
                                new Class<?>[]{java.sql.ResultSet.class}, (rowProxy, rowMethod, rowArguments) -> {
                                    Object value = invoke(result, rowMethod, rowArguments);
                                    if (rowMethod.getName().equals("close")) { throw new java.sql.SQLException("injected result-set close failure"); }
                                    return value;
                                });
                    }
                    if (method.getName().equals("close") && armed.get()) { throw new java.sql.SQLException("injected statement close failure"); }
                    return result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "oversized", "duplicate"})
    void boundsCorruptOrMissingCommittedMetadataBeforePublication(String fault) throws Exception {
        try (var fixture = fixture(JdbcPublicationObserver.NOOP)) {
            fixture.seed();
            long units = fixture.number("SELECT COUNT(*) FROM publication_unit");
            // Controlled corruption of the read facade exercises fail-closed row/byte admission, not normal writes.
            try (var connection = fixture.dataframe.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE corrupt_projection_state AS SELECT artifact,required_generation,"
                        + "projected_generation,requested_at_ms,projected_at_ms,last_error_code FROM artifact_projection_state");
                statement.execute("DROP VIEW artifact_projection_state");
                statement.execute("CREATE VIEW artifact_projection_state AS SELECT artifact,required_generation,"
                        + "projected_generation,requested_at_ms,projected_at_ms,last_error_code FROM corrupt_projection_state");
                if (fault.equals("missing")) { statement.execute("DELETE FROM corrupt_projection_state WHERE artifact='masks'"); }
                if (fault.equals("duplicate")) {
                    statement.execute("INSERT INTO corrupt_projection_state SELECT artifact,required_generation,"
                            + "projected_generation,requested_at_ms,projected_at_ms,last_error_code FROM corrupt_projection_state WHERE artifact='masks'");
                }
                if (fault.equals("oversized")) {
                    try (var update = connection.prepareStatement("UPDATE corrupt_projection_state SET last_error_code=? WHERE artifact='masks'")) {
                        update.setString(1, "x".repeat(100000)); update.executeUpdate();
                    }
                }
            }
            if (fault.equals("missing")) { assertThat(fixture.backend.directProjections().acknowledge(ack(1))).isFalse(); }
            else { assertThatThrownBy(() -> fixture.backend.directProjections().acknowledge(ack(1))).hasMessageContaining("Direct projection"); }
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit")).isEqualTo(units);
            assertTemporaryStateAbsent(fixture);
        }
    }

    @ParameterizedTest
    @EnumSource(value = JdbcPublicationObserver.Phase.class, names = {"HIDDEN_CHUNK", "PUBLICATION_STAGED"})
    void rollsBackAllProtocolEffectsAndOwnedTemporaryState(JdbcPublicationObserver.Phase boundary) throws Exception {
        var armed = new AtomicBoolean();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == boundary && armed.getAndSet(false)) { throw new IllegalStateException("injected control failure"); }
        })) {
            fixture.seed();
            long units = fixture.number("SELECT COUNT(*) FROM publication_unit");
            long epoch = epoch(fixture);
            armed.set(true);
            assertThatThrownBy(() -> fixture.backend.directProjections().acknowledge(ack(1)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("injected control failure");
            assertThat(fixture.backend.directProjections().load("masks").projectedGeneration().value()).isZero();
            assertThat(epoch(fixture)).isEqualTo(epoch);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit")).isEqualTo(units);
            assertTemporaryStateAbsent(fixture);
            assertThat(fixture.backend.directProjections().acknowledge(ack(1))).isTrue();
        }
    }

    @Test
    void interruptionBeforeCommitRollsBackAndRetainsTheInterrupt() throws Exception {
        var armed = new AtomicBoolean();
        try (var fixture = fixture((phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.PUBLICATION_STAGED && armed.getAndSet(false)) {
                Thread.currentThread().interrupt();
            }
        })) {
            fixture.seed(); armed.set(true);
            try {
                assertThatThrownBy(() -> fixture.backend.directProjections().acknowledge(ack(1)))
                        .hasMessageContaining("Direct projection");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(fixture.backend.directProjections().load("masks").projectedGeneration().value()).isZero();
            assertTemporaryStateAbsent(fixture);
        }
    }

    @Test
    void rejectsUnsafeUtcEvenForAnUnchangedControlAndRejectsOversizedPayload() throws Exception {
        var wall = new MutableClock();
        Path root = Files.createDirectory(temporary.resolve("clock"));
        try (var fixture = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, Map.of(), wall)) {
            fixture.seed();
            assertThat(fixture.backend.directProjections().acknowledge(ack(1))).isTrue();
            long units = fixture.number("SELECT COUNT(*) FROM publication_unit");
            wall.utc.set(StagedPublicationFixture.NOW.minusSeconds(3));
            assertThatThrownBy(() -> fixture.backend.directProjections().acknowledge(ack(1)))
                    .isInstanceOf(com.iocextractor.application.artifact.lifecycle.LifecycleClockUnsafeException.class);
            wall.utc.set(StagedPublicationFixture.NOW);
            assertThatThrownBy(() -> fixture.backend.directProjections().recordFailure("masks", new ProjectionGeneration(1), "x".repeat(100000)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte admission");
            assertThatThrownBy(() -> fixture.backend.publication().updateProjection(new JdbcStagedPublication.Unit(
                    "invalid", "pins", Set.of("artifact:masks"), true), "masks", mutation -> true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit")).isEqualTo(units);
        }
    }

    @ParameterizedTest
    @CsvSource({"HIDDEN_CHUNK,false", "PUBLICATION_STAGED,false", "COMMITTED,true"})
    void processLossLeavesEitherTheEntireAckOrNoneAndRetryIsSafe(String phase, boolean committed) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("kill-" + phase));
        StagedPublicationFaultIT.killAt(root, phase, 0, "ack");
        try (var fixture = new StagedPublicationFixture(root, false, JdbcPublicationObserver.NOOP)) {
            var store = fixture.backend.directProjections();
            assertThat(store.load("masks").projectedGeneration().value()).isEqualTo(committed ? 1 : 0);
            assertThat(fixture.number("SELECT COUNT(*) FROM masks")).isEqualTo(1);
            assertThat(fixture.number("SELECT COUNT(*) FROM confirmation_receipt")).isEqualTo(1);
            assertThat(fixture.number("SELECT COUNT(*) FROM publication_unit WHERE state<>'COMMITTED'")).isZero();
            long epoch = epoch(fixture);
            assertThat(store.acknowledge(ack(1))).isTrue();
            assertThat(epoch(fixture)).isEqualTo(epoch + (committed ? 0 : 1));
            assertTemporaryStateAbsent(fixture);
        }
    }

    private StagedPublicationFixture fixture(JdbcPublicationObserver observer) throws Exception {
        return new StagedPublicationFixture(Files.createDirectory(temporary.resolve("fixture")), true, observer);
    }

    private static ArtifactProjectionWorkStore store(StagedPublicationFixture fixture, boolean direct) {
        return direct ? fixture.backend.directProjections() : fixture.backend.projections();
    }

    private static ProjectionAcknowledgement ack(long generation) {
        return new ProjectionAcknowledgement("masks", new ProjectionGeneration(generation));
    }

    private static String error(StagedPublicationFixture fixture) throws Exception {
        return fixture.text("SELECT last_error_code FROM artifact_projection_state WHERE artifact='masks'");
    }

    private static long epoch(StagedPublicationFixture fixture) throws Exception {
        return fixture.number("SELECT version FROM publication_epoch WHERE scope='artifact:masks'");
    }

    private static void assertTemporaryStateAbsent(StagedPublicationFixture fixture) throws Exception {
        // Exhaust the pool so the check includes the borrowed control connection after return.
        var connections = new java.util.ArrayList<java.sql.Connection>();
        try {
            for (int index = 0; index < 4; index++) {
                var connection = fixture.dataframe.getConnection(); connections.add(connection);
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_temp_master WHERE name='direct_projection_state'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
            }
        } finally { for (var connection : connections) { connection.close(); } }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Control test release timed out"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> utc = new AtomicReference<>(StagedPublicationFixture.NOW);
        @Override public Instant instant() { return utc.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
