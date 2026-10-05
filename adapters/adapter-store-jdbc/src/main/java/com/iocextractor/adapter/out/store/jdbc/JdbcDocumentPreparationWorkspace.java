package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace;
import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.common.IocExtractorException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One disk-backed global reducer. The single connection/cache is shared by all artifact cursors. */
final class JdbcDocumentPreparationWorkspace implements DocumentPreparationWorkspace {
    private final Path directory;
    private final Path snapshot;
    private final Path database;
    private final DocumentPreparationLimits limits;
    private final ArtifactIdentityResolver identities;
    private final Map<String, ArtifactWritePolicy> policies;
    private final DocumentRowCodec codec;
    private final Runnable diskCheck;
    private final Runnable release;
    private final Connection connection;
    private final boolean replay;
    private final PreparedStatement originals;
    private final PreparedStatement candidates;
    private final PreparedStatement keepWinner;
    private final PreparedStatement replaceWinner;
    private long candidateOrdinal;
    private long originalOrdinal;
    private boolean sealed;
    private boolean discard;
    private boolean closed;
    private int cursors;
    private RowCursor<PreparedArtifactRow> activeCursor;

    JdbcDocumentPreparationWorkspace(Path directory, Path snapshot, DocumentPreparationLimits limits,
            ArtifactIdentityResolver identities, Map<String, ArtifactWritePolicy> policies,
            Runnable diskCheck, Runnable release) {
        this.directory = directory;
        this.snapshot = snapshot;
        this.database = directory.resolve("prepared.db");
        this.limits = limits;
        this.identities = identities;
        this.policies = Map.copyOf(policies);
        this.codec = new DocumentRowCodec(limits.maximumRowBytes(), limits.maximumFieldBytes());
        this.diskCheck = diskCheck;
        this.release = release;
        this.replay = Files.exists(directory.resolve("seal"));
        Connection opened = null;
        try {
            if (replay) { validateSeal(); }
            else {
                Files.deleteIfExists(database);
                Files.deleteIfExists(directory.resolve("prepared.db-journal"));
            }
            opened = DriverManager.getConnection("jdbc:sqlite:" + database);
            try (var statement = opened.createStatement()) {
                statement.execute("PRAGMA cache_size=-" + limits.cacheKiB());
                statement.execute("PRAGMA mmap_size=0");
                statement.execute("PRAGMA temp_store=FILE");
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.execute("PRAGMA synchronous=FULL");
                // Reserve room for the source, manifest and a worst-case DELETE journal.
                long available = limits.workspaceBytes() - Files.size(snapshot) - 65536;
                statement.execute("PRAGMA max_page_count=" + Math.max(8, available / 8192));
                if (!replay) {
                    statement.execute("CREATE TABLE originals(key TEXT PRIMARY KEY, first_ordinal INTEGER NOT NULL) WITHOUT ROWID");
                    statement.execute("CREATE TABLE candidate(ordinal INTEGER PRIMARY KEY, artifact TEXT NOT NULL, payload BLOB NOT NULL, eligible INTEGER NOT NULL, retained INTEGER NOT NULL)");
                    statement.execute("CREATE TABLE winner(artifact TEXT NOT NULL, equality_key TEXT NOT NULL, first_ordinal INTEGER NOT NULL, candidate_ordinal INTEGER NOT NULL, PRIMARY KEY(artifact,equality_key)) WITHOUT ROWID");
                    statement.execute("CREATE INDEX winner_order ON winner(artifact,first_ordinal)");
                } else { statement.execute("PRAGMA query_only=ON"); }
            }
            opened.setAutoCommit(false);
            this.connection = opened;
            originals = opened.prepareStatement(replay ? "SELECT first_ordinal FROM originals WHERE key=?"
                    : "INSERT INTO originals(key,first_ordinal) VALUES (?,?) ON CONFLICT(key) DO NOTHING");
            candidates = opened.prepareStatement(replay ? "SELECT artifact,payload,eligible,retained FROM candidate WHERE ordinal=?"
                    : "INSERT INTO candidate(ordinal,artifact,payload,eligible,retained) VALUES (?,?,?,?,?)");
            String winner = "INSERT INTO winner(artifact,equality_key,first_ordinal,candidate_ordinal) VALUES (?,?,?,?) ON CONFLICT(artifact,equality_key) ";
            keepWinner = replay ? null : opened.prepareStatement(winner + "DO NOTHING");
            replaceWinner = replay ? null : opened.prepareStatement(winner + "DO UPDATE SET candidate_ordinal=excluded.candidate_ordinal");
        } catch (IOException | SQLException | RuntimeException failure) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IocExtractorException("Cannot initialize document preparation workspace", failure);
        }
    }

    @Override
    public Path source() { return snapshot; }
    @Override
    public void discard() { discard = true; }

    @Override
    public void beginPromotion() {
        requireOpen();
        if (!sealed) { throw new IllegalStateException("Cannot promote an unsealed document"); }
        try { DocumentWorkspaceFiles.write(directory.resolve("promoting"), "PROMOTING\n"); }
        catch (IOException failure) { throw new IocExtractorException("Cannot mark document promotion", failure); }
    }

    @Override
    public boolean promotionStarted() { return Files.exists(directory.resolve("promoting")); }

    @Override
    public boolean firstOriginal(String key) {
        requireOpen();
        if (sealed) { throw new IllegalStateException("Document workspace is sealed"); }
        if (key.length() > limits.maximumFieldBytes()
                || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limits.maximumFieldBytes()) {
            throw new IocExtractorException("Original identity exceeds field limit");
        }
        long ordinal = ++originalOrdinal;
        try {
            if (replay) {
                originals.setString(1, key);
                try (var result = originals.executeQuery()) {
                    if (!result.next()) { throw new IocExtractorException("Replayed document original identity differs"); }
                    return result.getLong(1) == ordinal;
                }
            }
            originals.setString(1, key);
            originals.setLong(2, ordinal);
            return originals.executeUpdate() == 1;
        } catch (SQLException failure) { throw storageFailure(failure); }
    }

    @Override
    public void append(RoutedArtifactCandidate candidate, boolean eligible, boolean retainObservations) {
        requireOpen();
        if (sealed) { throw new IllegalStateException("Document workspace is sealed"); }
        var policy = Objects.requireNonNull(policies.get(candidate.artifact()), "artifact write policy");
        byte[] payload = codec.encode(candidate.row());
        long ordinal = ++candidateOrdinal;
        try {
            if (replay) {
                validateReplayedCandidate(ordinal, candidate.artifact(), payload, eligible, retainObservations);
                return;
            }
            candidates.setLong(1, ordinal);
            candidates.setString(2, candidate.artifact());
            candidates.setBytes(3, payload);
            candidates.setBoolean(4, eligible);
            candidates.setBoolean(5, retainObservations);
            candidates.executeUpdate();
            selectCandidate(candidate, policy, ordinal, eligible, retainObservations);
            if (ordinal % limits.batchRows() == 0) { connection.commit(); diskCheck.run(); }
        } catch (SQLException failure) { throw storageFailure(failure); }
    }

    private void validateReplayedCandidate(long ordinal, String artifact, byte[] payload,
            boolean eligible, boolean retainObservations) throws SQLException {
        candidates.setLong(1, ordinal);
        try (var result = candidates.executeQuery()) {
            if (!result.next() || !artifact.equals(result.getString(1))
                    || !java.util.Arrays.equals(payload, result.getBytes(2))
                    || eligible != result.getBoolean(3) || retainObservations != result.getBoolean(4)) {
                throw new IocExtractorException("Replayed document candidate differs from seal");
            }
        }
    }

    private void selectCandidate(RoutedArtifactCandidate candidate, ArtifactWritePolicy policy,
            long ordinal, boolean eligible, boolean retainObservations) throws SQLException {
        boolean retained = retainObservations && policy.duplicateSelection() == ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST;
        if (retained && !eligible) { return; }
        var material = identities.materialOf(candidate.artifact(), candidate.row().template())
                .orElseThrow(() -> new IocExtractorException("Routed candidate has no final identity"));
        String key = retained ? "ordinal:" + ordinal
                : material.definitionId().length() + ":" + material.definitionId() + material.keyCanonical();
        if (key.length() > limits.maximumRowBytes()
                || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limits.maximumRowBytes()) {
            throw new IocExtractorException("Final identity exceeds row limit");
        }
        String selection = candidate.row().template().value(policy.selectionColumn());
        boolean replace = policy.duplicateSelection() == ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY
                && selection != null && !selection.isBlank();
        var statement = replace ? replaceWinner : keepWinner;
        statement.setString(1, candidate.artifact());
        statement.setString(2, key);
        statement.setLong(3, ordinal);
        statement.setLong(4, ordinal);
        statement.executeUpdate();
    }

    @Override
    public List<ArtifactWritePlan> seal(List<ArtifactWritePlan> descriptors,
            DocumentPreparationSummary summary) {
        requireOpen();
        if (sealed) { throw new IllegalStateException("Document workspace is already sealed"); }
        try {
            connection.commit();
            if (count("candidate", null) != candidateOrdinal) {
                throw new IocExtractorException("Document seal candidate count mismatch");
            }
            String catalog = descriptors.stream().map(plan -> plan.artifactName() + ":" + plan.header())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (descriptors.size() > 128 || catalog.length() > 32768) {
                throw new IocExtractorException("Document artifact catalog exceeds limit");
            }
            var counts = new StringBuilder().append(summary.extracted()).append(':').append(summary.retained())
                    .append(':').append(candidateOrdinal).append(':').append(originalOrdinal);
            for (var severity : com.iocextractor.diagnostics.DiagnosticSeverity.values()) {
                counts.append(':').append(summary.diagnostics().count(severity));
            }
            catalog += "\ncounts:" + counts + ":suppressed:" + summary.diagnostics().suppressed();
            Path catalogFile = directory.resolve("catalog");
            if (replay && (Files.size(catalogFile) > 131072 || !Files.readString(catalogFile).equals(catalog))) {
                throw new IocExtractorException("Document seal schema mismatch");
            }
            if (!replay) {
                DocumentWorkspaceFiles.write(catalogFile, catalog);
                try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!result.next() || !"ok".equals(result.getString(1))) {
                        throw new IocExtractorException("Document workspace integrity check failed");
                    }
                }
                DocumentWorkspaceFiles.write(directory.resolve("seal.tmp"), checksum());
                Files.move(directory.resolve("seal.tmp"), directory.resolve("seal"), StandardCopyOption.ATOMIC_MOVE);
                DocumentWorkspaceFiles.syncDirectory(directory);
            } else { validateSeal(); }
            diskCheck.run();
            sealed = true;
            var plans = new ArrayList<ArtifactWritePlan>();
            for (var descriptor : descriptors) {
                String artifact = descriptor.artifactName();
                int count = Math.toIntExact(count("winner", artifact));
                plans.add(new ArtifactWritePlan(artifact, descriptor.header(), rows(artifact, count), descriptor.idSequence()));
            }
            return List.copyOf(plans);
        } catch (IOException | SQLException failure) { throw new IocExtractorException("Cannot seal document workspace", failure); }
    }

    private RowSource<PreparedArtifactRow> rows(String artifact, int size) {
        return new RowSource<>() {
            public int size() { return size; }
            public RowCursor<PreparedArtifactRow> open() { return cursor(artifact); }
        };
    }

    private RowCursor<PreparedArtifactRow> cursor(String artifact) {
        requireOpen();
        if (!sealed || cursors != 0) { throw new IllegalStateException("Workspace permits one sealed cursor at a time"); }
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement("SELECT c.payload FROM winner w JOIN candidate c ON c.ordinal=w.candidate_ordinal WHERE w.artifact=? ORDER BY w.first_ordinal");
            statement.setString(1, artifact);
            ResultSet rows = statement.executeQuery();
            cursors++;
            activeCursor = new PreparedRowCursor(statement, rows);
            return activeCursor;
        } catch (SQLException failure) {
            if (statement != null) { try { statement.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); } }
            throw storageFailure(failure);
        }
    }

    private final class PreparedRowCursor implements RowCursor<PreparedArtifactRow> {
        private final PreparedStatement statement;
        private final ResultSet rows;
        private PreparedArtifactRow value;
        private boolean cursorClosed;

        private PreparedRowCursor(PreparedStatement statement, ResultSet rows) {
            this.statement = statement;
            this.rows = rows;
        }

        public boolean next() {
            if (cursorClosed) { throw new IllegalStateException("Document cursor is closed"); }
            if (Thread.currentThread().isInterrupted()) { throw new IocExtractorException("Document promotion interrupted"); }
            try { value = rows.next() ? codec.decode(rows.getBytes(1)) : null; return value != null; }
            catch (SQLException failure) { throw storageFailure(failure); }
        }

        public PreparedArtifactRow value() { return Objects.requireNonNull(value, "current row"); }

        public void close() {
            if (cursorClosed) { return; }
            cursorClosed = true;
            value = null;
            cursors--;
            activeCursor = null;
            try { statement.close(); } catch (SQLException failure) { throw storageFailure(failure); }
        }
    }

    private long count(String table, String artifact) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + (artifact == null ? "" : " WHERE artifact=?"))) {
            if (artifact != null) { statement.setString(1, artifact); }
            try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private void validateSeal() throws IOException {
        if (Files.size(directory.resolve("seal")) != 64
                || !Files.readString(directory.resolve("seal")).equals(checksum())) {
            throw new IocExtractorException("Document workspace seal checksum mismatch");
        }
    }

    private String checksum() throws IOException {
        return com.iocextractor.application.artifact.ArtifactIdentityDefinition.sha256(
                hash(database) + hash(directory.resolve("identity")) + hash(directory.resolve("catalog")));
    }

    static String hash(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int size;
            while ((size = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) { throw new IocExtractorException("Document pin hashing interrupted"); }
                digest.update(buffer, 0, size);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }

    private void requireOpen() { if (closed) { throw new IllegalStateException("Document workspace is closed"); } }
    private static IocExtractorException storageFailure(SQLException failure) {
        return new IocExtractorException("Document preparation storage failed", failure);
    }

    @Override
    public void close() {
        if (closed) { return; }
        closed = true;
        RuntimeException failure = null;
        try {
            if (activeCursor != null) { activeCursor.close(); }
        } catch (RuntimeException error) { failure = error; }
        try {
            connection.close();
        } catch (SQLException error) { failure = closingFailure(failure, error); }
        try {
            if (discard || !sealed && !replay) {
                JdbcDocumentPreparationWorkspaceFactory.deletePrivateDirectory(directory);
            }
        } catch (IOException error) { failure = closingFailure(failure, error); }
        try { release.run(); } catch (RuntimeException error) { failure = closingFailure(failure, error); }
        if (failure != null) { throw new IocExtractorException("Document workspace close failed", failure); }
    }

    private static RuntimeException closingFailure(RuntimeException failure, Exception error) {
        if (failure == null) { return new IocExtractorException("Cannot close document preparation workspace", error); }
        failure.addSuppressed(error);
        return failure;
    }
}
