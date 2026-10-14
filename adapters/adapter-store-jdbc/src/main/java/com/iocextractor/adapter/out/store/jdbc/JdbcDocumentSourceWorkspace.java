package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.SourceMarker;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.refang.RefangRule;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Indexed disposable overlap/occurrence spool. No checkpoint here can authorize a commit. */
final class JdbcDocumentSourceWorkspace implements DocumentSourceWorkspace {
    private final Path database;
    private final DocumentTextSpool text;
    private final DocumentPreparationLimits limits;
    private final Runnable diskCheck;
    private final Connection connection;
    private final PreparedStatement overlap;
    private final PreparedStatement claim;
    private final PreparedStatement exact;
    private final PreparedStatement record;
    private final PreparedStatement attribute;
    private final java.util.EnumMap<IndicatorType, String> patterns = new java.util.EnumMap<>(IndicatorType.class);
    private long ordinal;
    private int accepted;
    private int mutations;
    private Cursor<?> activeCursor;
    private boolean closed;

    JdbcDocumentSourceWorkspace(Path directory, DocumentPreparationLimits limits, Runnable diskCheck) {
        database = directory.resolve("source-decisions.db");
        text = new DocumentTextSpool(directory, limits, diskCheck);
        this.limits = limits;
        this.diskCheck = diskCheck;
        Connection opened = null;
        try {
            Files.deleteIfExists(database);
            opened = DriverManager.getConnection("jdbc:sqlite:" + database);
            try (var statement = opened.createStatement()) {
                statement.execute("PRAGMA cache_size=-" + limits.cacheKiB() / 2);
                statement.execute("PRAGMA mmap_size=0");
                statement.execute("PRAGMA temp_store=FILE");
                // This invocation-local scratch is always rebuilt, even for a sealed replay.
                statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("PRAGMA max_page_count=" + Math.max(8, limits.workspaceBytes() / 4 / 4096));
                statement.execute("CREATE TABLE claim(start INTEGER PRIMARY KEY,end INTEGER NOT NULL,type TEXT NOT NULL)");
                statement.execute("CREATE TABLE decision(ordinal INTEGER PRIMARY KEY,start INTEGER NOT NULL,end INTEGER NOT NULL,type TEXT NOT NULL,value TEXT NOT NULL,accepted INTEGER NOT NULL,marker_position INTEGER,source TEXT)");
                statement.execute("CREATE INDEX accepted_position ON decision(start,ordinal) WHERE accepted=1");
            }
            opened.setAutoCommit(false);
            connection = opened;
            overlap = opened.prepareStatement("SELECT end FROM claim WHERE start<? ORDER BY start DESC LIMIT 1");
            claim = opened.prepareStatement("INSERT INTO claim(start,end,type) VALUES (?,?,?)");
            exact = opened.prepareStatement("SELECT type FROM claim WHERE start=? AND end=?");
            record = opened.prepareStatement("INSERT INTO decision(ordinal,start,end,type,value,accepted) VALUES (?,?,?,?,?,?)");
            attribute = opened.prepareStatement("UPDATE decision SET marker_position=?,source=? WHERE ordinal=?");
        } catch (IOException | SQLException | RuntimeException | Error failure) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { text.close(); Files.deleteIfExists(database); }
            catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof Error fatal) { throw fatal; }
            throw new IocExtractorException("Cannot initialize document source workspace", failure);
        }
    }
    public Writer writer() { requireOpen(); return text.writer(); }
    public CharSequence text() { requireOpen(); return text.text(); }
    public boolean isEmpty() { return text().isEmpty(); }
    public int replace(RefangRule rule) { requireOpen(); return text.replace(rule); }
    public int maximumMatchCharacters() { return limits.maximumFieldBytes(); }

    public boolean overlaps(int start, int end) {
        requireOpen();
        if (start == end) { return false; }
        try {
            overlap.setInt(1, end);
            try (var result = overlap.executeQuery()) { return result.next() && result.getInt(1) > start; }
        } catch (SQLException failure) { throw storageFailure(failure); }
    }
    public void record(ExtractionDecision decision) {
        requireOpen();
        validateField(decision.span().value()); validateField(decision.pattern());
        String previous = patterns.putIfAbsent(decision.type(), decision.pattern());
        if (previous != null && !previous.equals(decision.pattern())) { throw new IllegalStateException("Multiple patterns for one IOC type"); }
        boolean keep = decision.status() == ExtractionDecisionStatus.ACCEPTED;
        try {
            if (keep && decision.span().end() > decision.span().start()) {
                claim.setInt(1, decision.span().start()); claim.setInt(2, decision.span().end());
                claim.setString(3, decision.type().name()); claim.executeUpdate();
            }
            record.setLong(1, ++ordinal); record.setInt(2, decision.span().start()); record.setInt(3, decision.span().end());
            record.setString(4, decision.type().name());
            record.setString(5, decision.span().value()); record.setBoolean(6, keep); record.executeUpdate();
            if (keep) { accepted = Math.incrementExact(accepted); }
            mutated();
        } catch (SQLException failure) { throw storageFailure(failure); }
    }
    public Optional<IndicatorType> acceptedType(int start, int end) {
        requireOpen();
        try {
            exact.setInt(1, start); exact.setInt(2, end);
            try (var result = exact.executeQuery()) {
                return result.next() ? Optional.of(IndicatorType.valueOf(result.getString(1))) : Optional.empty();
            }
        } catch (SQLException failure) { throw storageFailure(failure); }
    }
    public RowSource<ExtractionDecision> decisions() {
        return rows(Math.toIntExact(ordinal), "SELECT ordinal,start,end,type,value,accepted FROM decision ORDER BY ordinal", false, result -> new ExtractionDecision(
                IndicatorType.valueOf(result.getString("type")), patterns.get(IndicatorType.valueOf(result.getString("type"))),
                new Span(result.getInt("start"), result.getInt("end"), result.getString("value")),
                result.getBoolean("accepted") ? ExtractionDecisionStatus.ACCEPTED : ExtractionDecisionStatus.DROPPED_OVERLAP));
    }
    public RowSource<RawIndicator> indicators() {
        return rows(accepted, "SELECT ordinal,start,type,value FROM decision WHERE accepted=1 ORDER BY start,ordinal", true, this::raw);
    }
    public void attribute(AttributionDecision decision) {
        requireOpen();
        if (activeCursor == null || !activeCursor.attributable || activeCursor.currentOrdinal < 1) {
            throw new IllegalStateException("Attribution requires the current accepted occurrence cursor");
        }
        if (!decision.rawIndicator().equals(activeCursor.value())) {
            throw new IllegalArgumentException("Attribution does not describe the current accepted occurrence");
        }
        try {
            SourceMarker marker = decision.marker().orElse(null);
            if (marker == null) { attribute.setNull(1, java.sql.Types.INTEGER); attribute.setNull(2, java.sql.Types.VARCHAR); }
            else {
                validateField(marker.label()); attribute.setInt(1, marker.position()); attribute.setString(2, marker.label());
            }
            attribute.setLong(3, activeCursor.currentOrdinal);
            if (attribute.executeUpdate() != 1) { throw new IllegalStateException("Attribution occurrence is missing"); }
            mutated();
        } catch (SQLException failure) { throw storageFailure(failure); }
    }
    public RowSource<AttributionDecision> attributions() {
        return rows(accepted, "SELECT ordinal,start,type,value,marker_position,source FROM decision WHERE accepted=1 ORDER BY start,ordinal", false, result -> {
            int position = result.getInt("marker_position");
            var marker = result.wasNull() ? Optional.<SourceMarker>empty()
                    : Optional.of(new SourceMarker(position, result.getString("source")));
            return new AttributionDecision(raw(result), marker);
        });
    }
    private RawIndicator raw(ResultSet result) throws SQLException {
        return new RawIndicator(result.getString("value"), IndicatorType.valueOf(result.getString("type")), result.getInt("start"));
    }
    private void validateField(String value) {
        if (value.length() > limits.maximumFieldBytes() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limits.maximumFieldBytes()) {
            throw new IocExtractorException("Source value exceeds admitted field limit");
        }
    }
    private void mutated() throws SQLException {
        if (++mutations % 1024 == 0) { flush(); }
    }
    private void flush() throws SQLException { connection.commit(); diskCheck.run(); }
    private void requireOpen() { if (closed) { throw new IllegalStateException("Document source workspace is closed"); } }
    private static IocExtractorException storageFailure(SQLException failure) {
        return new IocExtractorException("Document source scratch operation failed", failure);
    }
    private <T> RowSource<T> rows(int count, String sql, boolean attributable, Decoder<T> decoder) {
        return new RowSource<>() {
            public int size() { return count; }
            public RowCursor<T> open() {
                requireOpen();
                if (activeCursor != null) { throw new IllegalStateException("Only one source occurrence cursor may be open"); }
                try {
                    flush();
                    var statement = connection.prepareStatement(sql);
                    try { var cursor = new Cursor<>(statement, statement.executeQuery(), decoder, attributable); activeCursor = cursor; return cursor; }
                    catch (SQLException | RuntimeException | Error failure) {
                        try { statement.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                        throw failure;
                    }
                } catch (SQLException failure) { throw storageFailure(failure); }
            }
        };
    }
    public void close() {
        if (closed) { return; }
        closed = true;
        Throwable failure = null;
        try {
            if (activeCursor != null) { activeCursor.close(); }
        } catch (RuntimeException | Error caught) { failure = caught; }
        try { connection.close(); }
        catch (SQLException | RuntimeException | Error caught) { failure = DocumentWorkspaceFailures.accumulate(failure, caught); }
        try { text.close(); }
        catch (IOException | RuntimeException | Error caught) { failure = DocumentWorkspaceFailures.accumulate(failure, caught); }
        try { Files.deleteIfExists(database); }
        catch (IOException | RuntimeException | Error caught) { failure = DocumentWorkspaceFailures.accumulate(failure, caught); }
        if (failure instanceof Error fatal) { throw fatal; }
        if (failure != null) { throw new IocExtractorException("Document source workspace close failed", failure); }
    }

    @FunctionalInterface
    private interface Decoder<T> { T read(ResultSet result) throws SQLException; }
    private final class Cursor<T> implements RowCursor<T> {
        private final PreparedStatement statement;
        private final ResultSet result;
        private final Decoder<T> decoder;
        private final boolean attributable;
        private T value;
        private long currentOrdinal;
        private boolean finished;
        private Cursor(PreparedStatement statement, ResultSet result, Decoder<T> decoder, boolean attributable) {
            this.statement = statement; this.result = result; this.decoder = decoder;
            this.attributable = attributable;
        }
        public boolean next() {
            requireOpen();
            if (finished) { throw new IllegalStateException("Source cursor is closed"); }
            if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("Document source cursor interrupted"); }
            try {
                if (!result.next()) { value = null; currentOrdinal = 0; return false; }
                currentOrdinal = result.getLong("ordinal"); value = decoder.read(result); return true;
            } catch (SQLException failure) { throw storageFailure(failure); }
        }
        public T value() {
            if (value == null) { throw new IllegalStateException("Source cursor has no current value"); }
            return value;
        }
        public void close() {
            if (finished) { return; }
            finished = true; value = null;
            try (statement; result) { /* Both JDBC handles close; secondary SQL failure is suppressed. */ }
            catch (SQLException failure) { throw storageFailure(failure); }
            finally { activeCursor = null; }
        }
    }
}
