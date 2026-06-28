package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import com.iocextractor.application.sync.RemoteFetchRecord;
import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** SQLite-backed idempotency ledger for read-only remote fetch. */
public final class JdbcRemoteFetchLedger implements RemoteFetchLedger {

    private final JdbcClient jdbc;

    public JdbcRemoteFetchLedger(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
    }

    JdbcRemoteFetchLedger(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<RemoteFetchRecord> find(RemoteObjectIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return jdbc.sql("""
                        SELECT remote_path, remote_size, remote_mtime, status,
                               local_path, attempts, last_error, fetched_at, updated_at
                        FROM remote_fetch_ledger
                        WHERE remote_path = :remote_path
                          AND remote_size = :remote_size
                          AND remote_mtime = :remote_mtime
                        """)
                .param("remote_path", identity.path())
                .param("remote_size", identity.size())
                .param("remote_mtime", identity.modifiedAt().toString())
                .query((rs, rowNum) -> new RemoteFetchRecord(
                        new RemoteObjectIdentity(
                                rs.getString("remote_path"),
                                rs.getLong("remote_size"),
                                Instant.parse(rs.getString("remote_mtime"))),
                        RemoteFetchStatus.valueOf(rs.getString("status")),
                        rs.getString("local_path"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        parseNullable(rs.getString("fetched_at")),
                        Instant.parse(rs.getString("updated_at"))))
                .optional();
    }

    @Override
    public RemoteFetchRecord markFetched(RemoteObjectIdentity identity, String localPath, Instant fetchedAt) {
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException("localPath must not be blank");
        }
        return upsert(identity, RemoteFetchStatus.FETCHED, localPath, null,
                Objects.requireNonNull(fetchedAt, "fetchedAt"), fetchedAt);
    }

    @Override
    public RemoteFetchRecord markSkipped(RemoteObjectIdentity identity, String reason, Instant skippedAt) {
        return upsert(identity, RemoteFetchStatus.SKIPPED, null, reason, null,
                Objects.requireNonNull(skippedAt, "skippedAt"));
    }

    @Override
    public RemoteFetchRecord markFailed(RemoteObjectIdentity identity, String reason, Instant failedAt) {
        return upsert(identity, RemoteFetchStatus.FAILED, null, requireReason(reason), null,
                Objects.requireNonNull(failedAt, "failedAt"));
    }

    private RemoteFetchRecord upsert(RemoteObjectIdentity identity,
                                     RemoteFetchStatus status,
                                     String localPath,
                                     String lastError,
                                     Instant fetchedAt,
                                     Instant updatedAt) {
        Objects.requireNonNull(identity, "identity");
        jdbc.sql("""
                        INSERT INTO remote_fetch_ledger(
                            remote_path, remote_size, remote_mtime, status,
                            local_path, attempts, last_error, fetched_at, updated_at)
                        VALUES (
                            :remote_path, :remote_size, :remote_mtime, :status,
                            :local_path, :attempts, :last_error, :fetched_at, :updated_at)
                        ON CONFLICT(remote_path, remote_size, remote_mtime) DO UPDATE SET
                            status = excluded.status,
                            local_path = excluded.local_path,
                            attempts = CASE
                                WHEN excluded.status = 'FAILED' THEN remote_fetch_ledger.attempts + 1
                                ELSE remote_fetch_ledger.attempts
                            END,
                            last_error = excluded.last_error,
                            fetched_at = excluded.fetched_at,
                            updated_at = excluded.updated_at
                        """)
                .param("remote_path", identity.path())
                .param("remote_size", identity.size())
                .param("remote_mtime", identity.modifiedAt().toString())
                .param("status", status.name())
                .param("local_path", localPath)
                .param("attempts", status == RemoteFetchStatus.FAILED ? 1 : 0)
                .param("last_error", lastError)
                .param("fetched_at", fetchedAt == null ? null : fetchedAt.toString())
                .param("updated_at", updatedAt.toString())
                .update();
        return find(identity).orElseThrow();
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return reason;
    }

    private Instant parseNullable(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
