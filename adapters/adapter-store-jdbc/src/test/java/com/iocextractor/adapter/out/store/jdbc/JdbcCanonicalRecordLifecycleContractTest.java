package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.artifact.lifecycle.RecordValidityPolicy;
import com.iocextractor.application.tck.lifecycle.CanonicalRecordLifecycleContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the reusable canonical lifecycle contract against real SQLite storage. */
class JdbcCanonicalRecordLifecycleContractTest extends CanonicalRecordLifecycleContractTest {

    @TempDir
    Path tempDir;

    private final AtomicInteger fixtureSequence = new AtomicInteger();
    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void closeDataSources() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Override
    protected LifecycleFixture createFixture(LifecycleTimeSource timeSource,
                                             RecordValidityPolicy policy) {
        HikariDataSource dataSource = dataSource();
        var schema = new DataframeArtifactSchema(ARTIFACT, List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn("value", "TEXT"),
                new DataframeColumn("source", "TEXT")));
        List<DataframeArtifactSchema> schemas = List.of(schema);
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        var identityDefinition = new ArtifactIdentityDefinition(
                ARTIFACT,
                new CanonicalKeyDefinition("mask-row-v1", CanonicalKeyMode.COMPOSITE, List.of("value")),
                List.of(new CanonicalKeyDefinition(
                        "mask-value-v1", CanonicalKeyMode.COMPOSITE, List.of("value"))),
                1);
        new JdbcArtifactIdentityStore(dataSource, Clock.fixed(START, ZoneOffset.UTC))
                .ensure(identityDefinition);
        activate(dataSource, schemas);

        Clock allocatorClock = Clock.fixed(START, ZoneOffset.UTC);
        var writer = new JdbcCanonicalLifecycleWriter(
                dataSource,
                schemas,
                List.of(new ArtifactIdAllocatorDefinition(
                        ARTIFACT, ArtifactIdStrategy.ASCENDING, 1, 1)),
                timeSource,
                policy,
                allocatorClock,
                List.of(identityDefinition));
        return new LifecycleFixture(
                writer,
                new JdbcActiveArtifactReader(dataSource, schemas),
                new JdbcExpiredArtifactStore(dataSource, schemas));
    }

    private void activate(HikariDataSource dataSource, List<DataframeArtifactSchema> schemas) {
        var control = new JdbcLifecycleControlStore(dataSource, schemas);
        var disabled = control.load();
        var activating = disabled.beginActivation("contract-fixed-1h-v1");
        if (!control.compareAndSet(disabled, activating)) {
            throw new IllegalStateException("Failed to begin lifecycle activation");
        }
        if (!control.compareAndSet(activating, activating.completeActivation(EffectiveTime.at(START)))) {
            throw new IllegalStateException("Failed to complete lifecycle activation");
        }
    }

    private HikariDataSource dataSource() {
        Path database = tempDir.resolve("lifecycle-contract-" + fixtureSequence.incrementAndGet() + ".db");
        HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + database, "low-memory", 4, 4));
        dataSources.add(dataSource);
        return dataSource;
    }
}
