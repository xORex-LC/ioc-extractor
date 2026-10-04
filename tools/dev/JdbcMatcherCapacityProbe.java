package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.sqlite.ProgressHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Opt-in exact-driver work screen against the actual packaged canonical matcher. */
public final class JdbcMatcherCapacityProbe {
    private JdbcMatcherCapacityProbe() { }

    public static void main(String[] args) throws Exception {
        int aliases = Integer.parseInt(args[0]);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createFixture(connection, aliases);
            var captured = new LinkedHashSet<String>();
            Connection observed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, values) -> {
                        if (method.getName().equals("prepareStatement") && values[0] instanceof String sql
                                && sql.contains("canonical_match_alias")) {
                            captured.add(sql);
                        }
                        try {
                            return method.invoke(connection, values);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var schema = new DataframeArtifactSchema("ioc_aggregate", List.of(new DataframeColumn("url_match")));
            var planner = new JdbcCanonicalMatchPlanner(new SingleConnectionDataSource(connection, true), List.of(schema));
            var time = EffectiveTime.at(Instant.ofEpochMilli(100));
            var shapes = List.of(
                    List.of(request("hit", 1)),
                    List.of(request("miss", aliases + 1)),
                    List.of(new CanonicalMatchRequest("two-keys", List.of(key(1), key(aliases + 1)))),
                    List.of(request("first", 1), request("last", aliases), request("missing", aliases + 1),
                            new CanonicalMatchRequest("empty", List.of())));
            int[] expected = {1, 0, 1, 2};
            var names = List.of("singleton-hit", "singleton-miss", "multi-key", "multi-request");
            try (Statement sql = connection.createStatement(); ResultSet version = sql.executeQuery("SELECT sqlite_version()")) {
                version.next();
                System.out.println("{\"aliases\":" + aliases + ",\"sqliteVersion\":" + quote(version.getString(1))
                        + ",\"javaVersion\":" + quote(System.getProperty("java.version")) + "}");
            }
            for (int index = 0; index < shapes.size(); index++) {
                var requests = shapes.get(index);
                long[] callbacks = {0};
                // A fine counter cross-check complements the historical 1000-step screen.
                ProgressHandler.setHandler(connection, 1, new ProgressHandler() {
                    @Override protected int progress() { callbacks[0]++; return 0; }
                });
                try {
                    var plans = planner.plan(observed, schema, time, requests);
                    int hits = plans.stream().mapToInt(plan -> plan.candidates().size()).sum();
                    if (hits != expected[index] || plans.size() != requests.size()) {
                        throw new AssertionError("Independent hit/miss oracle failed: " + names.get(index));
                    }
                } finally {
                    ProgressHandler.clearHandler(connection);
                }
                System.out.println("{\"aliases\":" + aliases + ",\"shape\":" + quote(names.get(index))
                        + ",\"vmCallbacksQuantum1\":" + callbacks[0] + ",\"hits\":" + expected[index] + "}");
                if (callbacks[0] > 10000) {
                    throw new AssertionError("Selective matcher exceeded work screen: " + names.get(index));
                }
            }
            explain(connection, captured, aliases, "mechanism-fixture");
            if (args.length > 1) {
                try (Connection populated = DriverManager.getConnection("jdbc:sqlite:file:" + args[1] + "?mode=ro")) {
                    explain(populated, captured, aliases, "public-application-state");
                }
            }
        }
    }

    private static void explain(Connection connection, java.util.Set<String> captured, int aliases,
                                String scope) throws Exception {
        // Explain captured production SQL; no business rows are modified.
        try (Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS temp.ioc_match_request");
            sql.execute("CREATE TEMP TABLE ioc_match_request(request_order INTEGER NOT NULL,request_id TEXT NOT NULL,"
                    + "definition_id TEXT NOT NULL,key_hash TEXT NOT NULL,key_canonical TEXT NOT NULL,"
                    + "PRIMARY KEY(request_id,definition_id,key_hash,key_canonical))");
        }
        for (String query : captured) {
            var descriptions = new ArrayList<String>();
            boolean staged = query.contains("r.request_id");
            try (PreparedStatement sql = connection.prepareStatement("EXPLAIN QUERY PLAN " + query)) {
                sql.setString(1, "ioc_aggregate");
                if (staged) {
                    sql.setLong(2, 100);
                } else {
                    sql.setString(2, "capacity-v1"); sql.setString(3, key(1).keyHash());
                    sql.setString(4, key(1).keyCanonical()); sql.setLong(5, 100);
                }
                try (ResultSet rows = sql.executeQuery()) {
                    while (rows.next()) { descriptions.add(rows.getString(4)); }
                }
            }
            if (descriptions.stream().noneMatch(value -> value.contains("artifact=? AND definition_id=?"
                    + " AND key_hash=? AND key_canonical=?"))) {
                throw new AssertionError("Full-key index terms absent: " + descriptions);
            }
            System.out.println("{\"aliases\":" + aliases + ",\"planScope\":" + quote(scope) + ",\"plan\":["
                    + descriptions.stream().map(JdbcMatcherCapacityProbe::quote)
                            .collect(java.util.stream.Collectors.joining(",")) + "]}");
        }
    }

    private static CanonicalMatchRequest request(String id, int value) {
        return new CanonicalMatchRequest(id, List.of(key(value)));
    }

    private static CanonicalKeyMaterial key(int value) {
        return new CanonicalKeyMaterial("capacity-v1", String.format(Locale.ROOT, "%064x", value), "key-" + value);
    }

    private static void createFixture(Connection connection, int count) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE ioc_aggregate(id INTEGER PRIMARY KEY,row_key TEXT NOT NULL UNIQUE,"
                    + "_lifecycle_id INTEGER,_valid_until_epoch_ms INTEGER)");
            sql.execute("CREATE TABLE canonical_match_alias(artifact TEXT NOT NULL,definition_id TEXT NOT NULL,"
                    + "key_hash TEXT NOT NULL,key_canonical TEXT NOT NULL,lifecycle_id INTEGER NOT NULL,"
                    + "canonical_row_id INTEGER NOT NULL,PRIMARY KEY(artifact,definition_id,key_hash,key_canonical,lifecycle_id))");
            sql.execute("CREATE INDEX ix_canonical_match_alias_lookup "
                    + "ON canonical_match_alias(artifact,definition_id,key_hash,key_canonical)");
            sql.execute("CREATE INDEX ix_canonical_match_alias_lifecycle ON canonical_match_alias(artifact,lifecycle_id)");
        }
        connection.setAutoCommit(false);
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO ioc_aggregate VALUES(?,?,?,1000)");
             PreparedStatement alias = connection.prepareStatement("INSERT INTO canonical_match_alias VALUES(?,?,?,?,?,?)")) {
            for (int value = 1; value <= count; value++) {
                var key = key(value);
                row.setInt(1, value); row.setString(2, key.keyCanonical()); row.setInt(3, value); row.addBatch();
                alias.setString(1, "ioc_aggregate"); alias.setString(2, key.definitionId());
                alias.setString(3, key.keyHash()); alias.setString(4, key.keyCanonical());
                alias.setInt(5, value); alias.setInt(6, value); alias.addBatch();
                if (value % 256 == 0) { row.executeBatch(); alias.executeBatch(); }
            }
            row.executeBatch(); alias.executeBatch();
        }
        connection.commit(); connection.setAutoCommit(true);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
