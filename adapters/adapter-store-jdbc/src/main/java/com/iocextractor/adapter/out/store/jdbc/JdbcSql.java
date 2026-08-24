package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Shared, grammar-safe SQL construction primitives for the JDBC adapter. */
final class JdbcSql {

    private JdbcSql() {
    }

    static long epochMillis(EffectiveTime time) {
        return time.value().toEpochMilli();
    }

    static String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(ignored -> "?")
                .collect(Collectors.joining(", "));
    }

    static String joinedQuoted(List<String> identifiers) {
        return identifiers.stream().map(JdbcSql::quote).collect(Collectors.joining(", "));
    }

    static String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        statement.clearParameters();
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }
}
