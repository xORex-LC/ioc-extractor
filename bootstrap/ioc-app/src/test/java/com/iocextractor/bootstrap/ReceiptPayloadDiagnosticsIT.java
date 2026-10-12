package com.iocextractor.bootstrap;

import com.iocextractor.application.tck.junit.IntegrationTest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Exact SQLite page accounting without multiplying full dbstat traversals by family count. */
@IntegrationTest
class ReceiptPayloadDiagnosticsIT {
    @TempDir Path directory;

    @Test
    void inventoriesTablesAndIndexesWithOneDbstatTraversal() throws Exception {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("receipt-census.db"));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE publication_native_schema(name TEXT PRIMARY KEY)");
            statement.execute("CREATE TABLE publication_version_masks_receipt_rows(receipt_id TEXT,ordinal INTEGER,row_key TEXT)");
            statement.execute("CREATE INDEX mask_receipt_lookup ON publication_version_masks_receipt_rows(receipt_id,ordinal)");
            statement.execute("CREATE TABLE publication_version_confirmation_receipt_field_position"
                    + "(receipt_id TEXT,artifact TEXT,ordinal INTEGER,field_name TEXT,occurrence_position INTEGER)");
            statement.execute("CREATE TABLE publication_version_empty_receipt_rows(receipt_id TEXT,ordinal INTEGER,row_key TEXT)");
            statement.execute("CREATE VIEW masks_receipt_rows AS SELECT * FROM publication_version_masks_receipt_rows");
            statement.execute("CREATE VIEW confirmation_receipt_field_position AS SELECT * FROM"
                    + " publication_version_confirmation_receipt_field_position");
            statement.execute("INSERT INTO publication_native_schema VALUES"
                    + "('masks_receipt_rows'),('confirmation_receipt_field_position'),('empty_receipt_rows')");
            statement.execute("INSERT INTO publication_version_masks_receipt_rows VALUES ('receipt',0,'first'),('receipt',1,'second')");
            statement.execute("INSERT INTO publication_version_confirmation_receipt_field_position"
                    + " VALUES ('receipt','masks',0,'source',7)");
        }
        var traversals = new AtomicInteger();
        var counted = new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("prepareStatement") && arguments[0].toString().contains(" dbstat")) {
                                traversals.incrementAndGet();
                            }
                            Object result;
                            try { result = method.invoke(connection, arguments); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                            return method.getName().equals("createStatement") ? countQueries((Statement) result, traversals) : result;
                        });
            }
        };

        var result = ReceiptPayloadDiagnostics.inspect(counted);

        assertThat(traversals).hasValue(1);
        assertThat(result.get("families")).isEqualTo(List.of(
                expectedFamily(source, "confirmation_receipt_field_position", 1),
                expectedFamily(source, "empty_receipt_rows", 0),
                expectedFamily(source, "masks_receipt_rows", 2)));
        assertThat(result.get("readPlans")).isInstanceOf(Map.class);
    }

    private static Statement countQueries(Statement statement, AtomicInteger traversals) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("executeQuery") && arguments[0].toString().contains(" dbstat")) {
                        traversals.incrementAndGet();
                    }
                    try { return method.invoke(statement, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static Map<String, Object> expectedFamily(SQLiteDataSource source, String name, long rows) throws SQLException {
        String physical = "publication_version_" + name;
        var pages = new LinkedHashMap<String, List<Long>>();
        try (var connection = source.getConnection(); var names = connection.prepareStatement(
                "SELECT name FROM sqlite_schema WHERE name=? OR (type='index' AND tbl_name=?) ORDER BY name")) {
            names.setString(1, physical); names.setString(2, physical);
            try (var objects = names.executeQuery()) {
                while (objects.next()) {
                    String object = objects.getString(1);
                    try (var query = connection.prepareStatement("SELECT SUM(pgsize),SUM(payload) FROM dbstat WHERE name=?")) {
                        query.setString(1, object);
                        try (var stats = query.executeQuery()) {
                            stats.next(); pages.put(object, List.of(stats.getLong(1), stats.getLong(2)));
                        }
                    }
                }
            }
        }
        return Map.of("family", name, "receiptPayload", true, "physicalRows", rows, "pageAndPayloadBytes", pages);
    }
}
