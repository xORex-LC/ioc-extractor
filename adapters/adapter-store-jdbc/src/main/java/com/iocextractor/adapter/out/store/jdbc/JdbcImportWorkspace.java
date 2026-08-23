package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceCapacity;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.dataframeimport.ImportWorkspaceException;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Per-delivery SQLite workspace with explicit rebuild and sealed read-only
 * verification. Scratch data is isolated under opaque hashed file names.
 */
public final class JdbcImportWorkspace implements ImportWorkspace {

    private final ImportWorkspaceLayout layout;
    private final ImportWorkspaceLimits limits;
    private final Clock clock;
    private final AtomicReference<ImportWorkspaceCapacity.State> capacityState =
            new AtomicReference<>(ImportWorkspaceCapacity.State.ACCEPTING);

    /** Creates a workspace rooted at a private service-owned directory. */
    public JdbcImportWorkspace(Path root, ImportWorkspaceLimits limits, Clock clock) {
        this.layout = new ImportWorkspaceLayout(root);
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ImportWorkspaceWriter create(CreateImportWorkspaceCommand command) {
        Objects.requireNonNull(command, "command");
        requireAdmissionCapacity();
        ImportWorkspaceLayout.WorkspacePaths paths = layout.paths(command.deliveryId());
        try {
            Files.createDirectories(layout.root());
            if (Files.exists(paths.building()) || Files.exists(paths.sealed())) {
                throw new ImportWorkspaceException(
                        ImportWorkspaceException.Reason.INCOMPATIBLE_EXISTING_STAGE,
                        "Import workspace already exists and requires explicit recovery or rebuild");
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.building());
            try {
                ImportWorkspaceSchema.create(connection);
                insertMeta(connection, command);
                connection.setAutoCommit(false);
                return new JdbcImportWorkspaceWriter(
                        connection, command, paths, limits, clock,
                        this::requireGrowthCapacity, this::sealFile);
            } catch (RuntimeException | SQLException failure) {
                closeAfterFailure(connection, failure);
                throw failure;
            }
        } catch (ImportWorkspaceException failure) {
            throw failure;
        } catch (SQLException | IOException failure) {
            throw storageFailure("Cannot create import workspace", failure);
        }
    }

    @Override
    public ImportWorkspaceWriter rebuild(CreateImportWorkspaceCommand command) {
        Objects.requireNonNull(command, "command");
        ImportWorkspaceLayout.WorkspacePaths paths = layout.paths(command.deliveryId());
        try {
            deleteWorkspace(paths.building());
            deleteWorkspace(paths.sealed());
        } catch (IOException failure) {
            throw storageFailure("Cannot rebuild import workspace", failure);
        }
        return create(command);
    }

    @Override
    public ImportStage verifySealed(CreateImportWorkspaceCommand command, ImportStage expected) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(expected, "expected");
        layout.requireReference(command.deliveryId(), expected.reference());
        ImportWorkspaceLayout.WorkspacePaths paths = layout.paths(command.deliveryId());
        if (!Files.isRegularFile(paths.sealed())) {
            throw new ImportWorkspaceException(
                    ImportWorkspaceException.Reason.STAGE_NOT_SEALED,
                    "Import stage is not sealed");
        }
        ImportSha256 actualDigest = digest(paths.sealed());
        if (!actualDigest.equals(expected.digest())) {
            throw new ImportWorkspaceException(
                    ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED,
                    "Import stage digest does not match pinned evidence");
        }
        String url = "jdbc:sqlite:file:" + paths.sealed().toAbsolutePath() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                try (ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!resultSet.next() || !"ok".equals(resultSet.getString(1))) {
                        throw integrityFailure("Import stage SQLite integrity check failed");
                    }
                }
            }
            verifyMeta(connection, command, expected);
            return expected;
        } catch (ImportWorkspaceException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new ImportWorkspaceException(
                    ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED,
                    "Cannot verify sealed import stage", failure);
        }
    }

    @Override
    public ImportWorkspaceCapacity capacity() {
        long used = workspaceBytes();
        ImportWorkspaceCapacity.State previous = capacityState.get();
        ImportWorkspaceCapacity.State next;
        if (used >= limits.maximumWorkspaceBytes()) {
            next = ImportWorkspaceCapacity.State.EXHAUSTED;
        } else if (previous != ImportWorkspaceCapacity.State.ACCEPTING
                && used > limits.resumeAtBytes()) {
            next = ImportWorkspaceCapacity.State.PAUSED;
        } else if (used >= limits.pauseAtBytes()) {
            next = ImportWorkspaceCapacity.State.PAUSED;
        } else {
            next = ImportWorkspaceCapacity.State.ACCEPTING;
        }
        capacityState.set(next);
        return new ImportWorkspaceCapacity(used, limits.maximumWorkspaceBytes(), next);
    }

    private void insertMeta(Connection connection, CreateImportWorkspaceCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stage_meta(
                    delivery_id, schema_version, snapshot_sha256, snapshot_size,
                    contract_id, contract_version, contract_fingerprint,
                    duplicate_policy, plan_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, command.deliveryId().value());
            statement.setInt(2, ImportWorkspaceSchema.VERSION);
            statement.setString(3, command.snapshot().digest().value());
            statement.setLong(4, command.snapshot().size());
            statement.setString(5, command.contract().id().value());
            statement.setInt(6, command.contract().version());
            statement.setString(7, command.contract().fingerprint().value());
            statement.setString(8, command.duplicatePolicy().name());
            statement.setString(9, planHash(command));
            statement.executeUpdate();
        }
    }

    private void verifyMeta(Connection connection,
                            CreateImportWorkspaceCommand command,
                            ImportStage expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT schema_version, snapshot_sha256, snapshot_size,
                       contract_id, contract_version, contract_fingerprint,
                       duplicate_policy, source_row_count, accepted_count,
                       rejected_count, plan_hash, sealed_at_ms
                FROM stage_meta
                WHERE delivery_id = ?
                """)) {
            statement.setString(1, command.deliveryId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                boolean valid = resultSet.next()
                        && resultSet.getInt("schema_version") == ImportWorkspaceSchema.VERSION
                        && command.snapshot().digest().value().equals(resultSet.getString("snapshot_sha256"))
                        && command.snapshot().size() == resultSet.getLong("snapshot_size")
                        && command.contract().id().value().equals(resultSet.getString("contract_id"))
                        && command.contract().version() == resultSet.getInt("contract_version")
                        && command.contract().fingerprint().value().equals(
                                resultSet.getString("contract_fingerprint"))
                        && command.duplicatePolicy().name().equals(resultSet.getString("duplicate_policy"))
                        && expected.sourceRows() == resultSet.getLong("source_row_count")
                        && expected.acceptedRows() == resultSet.getLong("accepted_count")
                        && expected.rejectedRows() == resultSet.getLong("rejected_count")
                        && planHash(command).equals(resultSet.getString("plan_hash"))
                        && resultSet.getObject("sealed_at_ms") != null
                        && !resultSet.next();
                if (!valid) {
                    throw integrityFailure("Import stage metadata does not match pinned evidence");
                }
            }
        }
    }

    private ImportStage sealFile(ImportWorkspaceLayout.WorkspacePaths paths,
                                 long sourceRows,
                                 long acceptedRows,
                                 long rejectedRows) {
        try {
            force(paths.building());
            Files.move(paths.building(), paths.sealed(), StandardCopyOption.ATOMIC_MOVE);
            force(layout.root());
            ImportSha256 digest = digest(paths.sealed());
            return new ImportStage(paths.reference(), digest, sourceRows, acceptedRows, rejectedRows);
        } catch (IOException failure) {
            throw storageFailure("Cannot seal import workspace", failure);
        }
    }

    private void requireAdmissionCapacity() {
        ImportWorkspaceCapacity current = capacity();
        if (current.state() == ImportWorkspaceCapacity.State.PAUSED) {
            throw new ImportWorkspaceException(
                    ImportWorkspaceException.Reason.CAPACITY_PAUSED,
                    "Import workspace admission is paused at its capacity watermark");
        }
        if (current.state() == ImportWorkspaceCapacity.State.EXHAUSTED) {
            throw new ImportWorkspaceException(
                    ImportWorkspaceException.Reason.HARD_LIMIT_EXCEEDED,
                    "Import workspace hard capacity is exhausted");
        }
    }

    private void requireGrowthCapacity() {
        if (capacity().state() == ImportWorkspaceCapacity.State.EXHAUSTED) {
            throw new ImportWorkspaceException(
                    ImportWorkspaceException.Reason.HARD_LIMIT_EXCEEDED,
                    "Import workspace hard capacity is exhausted");
        }
    }

    private long workspaceBytes() {
        if (!Files.exists(layout.root())) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(layout.root())) {
            return paths.filter(Files::isRegularFile).mapToLong(this::size).sum();
        } catch (IOException failure) {
            throw storageFailure("Cannot inspect import workspace capacity", failure);
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            throw storageFailure("Cannot inspect import workspace capacity", failure);
        }
    }

    private ImportSha256 digest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                 DigestInputStream hashing = new DigestInputStream(input, digest)) {
                hashing.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return new ImportSha256(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        } catch (IOException failure) {
            throw storageFailure("Cannot hash import workspace", failure);
        }
    }

    private String planHash(CreateImportWorkspaceCommand command) {
        String descriptor = String.join("\u001f",
                "stage-v" + ImportWorkspaceSchema.VERSION,
                command.snapshot().digest().value(),
                Long.toString(command.snapshot().size()),
                command.contract().id().value(),
                Integer.toString(command.contract().version()),
                command.contract().fingerprint().value(),
                command.duplicatePolicy().name());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private void deleteWorkspace(Path database) throws IOException {
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
        Files.deleteIfExists(database);
    }

    private void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void closeAfterFailure(Connection connection, Throwable primary) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private ImportWorkspaceException integrityFailure(String message) {
        return new ImportWorkspaceException(
                ImportWorkspaceException.Reason.STAGE_INTEGRITY_FAILED, message);
    }

    private ImportWorkspaceException storageFailure(String message, Throwable cause) {
        return new ImportWorkspaceException(
                ImportWorkspaceException.Reason.STORAGE_FAILURE, message, cause);
    }

    @FunctionalInterface
    interface SealOperation {
        ImportStage seal(ImportWorkspaceLayout.WorkspacePaths paths,
                         long sourceRows,
                         long acceptedRows,
                         long rejectedRows);
    }
}
