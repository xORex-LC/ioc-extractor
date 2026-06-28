package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite-backed per-slice/per-target publish ledger with CAS transitions. */
public final class JdbcPublishLedger implements PublishLedger {

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcPublishLedger(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    JdbcPublishLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PublishRecord ensurePending(PublishRecord pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.status() != PublishStatus.PENDING) {
            throw new IllegalArgumentException("ensurePending accepts only PENDING records");
        }
        jdbc.sql("""
                        INSERT INTO publish_ledger(
                            slice_id, target_id, profile, slice_name, manifest_sha256,
                            endpoint, remote_path, status, attempts, last_error,
                            remote_verification, created_at, updated_at)
                        VALUES (
                            :slice_id, :target_id, :profile, :slice_name, :manifest_sha256,
                            :endpoint, :remote_path, 'PENDING', 0, NULL,
                            NULL, :created_at, :updated_at)
                        ON CONFLICT(slice_id, target_id) DO NOTHING
                        """)
                .param("slice_id", pending.sliceId())
                .param("target_id", pending.targetId())
                .param("profile", pending.profile())
                .param("slice_name", pending.sliceName())
                .param("manifest_sha256", pending.manifestSha256())
                .param("endpoint", pending.endpoint())
                .param("remote_path", pending.remotePath())
                .param("created_at", pending.createdAt().toString())
                .param("updated_at", pending.updatedAt().toString())
                .update();
        PublishRecord existing = find(pending.sliceId(), pending.targetId()).orElseThrow();
        if (!sameBinding(existing, pending)) {
            throw new IllegalStateException("Publish ledger pair already exists with different slice/target binding: "
                    + pending.sliceId() + "/" + pending.targetId());
        }
        return existing;
    }

    @Override
    public Optional<PublishRecord> find(String sliceId, String targetId) {
        return jdbc.sql("""
                        SELECT slice_id, target_id, profile, slice_name, manifest_sha256,
                               endpoint, remote_path, status, attempts, last_error,
                               remote_verification, created_at, updated_at
                        FROM publish_ledger
                        WHERE slice_id = :slice_id AND target_id = :target_id
                        """)
                .param("slice_id", requireText(sliceId, "sliceId"))
                .param("target_id", requireText(targetId, "targetId"))
                .query(JdbcPublishLedger::map)
                .optional();
    }

    @Override
    public List<PublishRecord> findBySlice(String sliceId) {
        return jdbc.sql("""
                        SELECT slice_id, target_id, profile, slice_name, manifest_sha256,
                               endpoint, remote_path, status, attempts, last_error,
                               remote_verification, created_at, updated_at
                        FROM publish_ledger
                        WHERE slice_id = :slice_id
                        ORDER BY target_id
                        """)
                .param("slice_id", requireText(sliceId, "sliceId"))
                .query(JdbcPublishLedger::map)
                .list();
    }

    @Override
    public List<PublishRecord> findRetryable() {
        return jdbc.sql("""
                        SELECT slice_id, target_id, profile, slice_name, manifest_sha256,
                               endpoint, remote_path, status, attempts, last_error,
                               remote_verification, created_at, updated_at
                        FROM publish_ledger
                        WHERE status IN ('PENDING', 'FAILED')
                        ORDER BY created_at, slice_id, target_id
                        """)
                .query(JdbcPublishLedger::map)
                .list();
    }

    @Override
    public PublishRecord transition(String sliceId,
                                    String targetId,
                                    PublishStatus expected,
                                    PublishStatus next,
                                    String lastError,
                                    String remoteVerification) {
        requireTransition(expected, next);
        Instant now = clock.instant();
        int affected = jdbc.sql("""
                        UPDATE publish_ledger
                        SET status = :next,
                            attempts = CASE
                                WHEN :next IN ('IN_PROGRESS', 'FAILED') THEN attempts + 1
                                ELSE attempts
                            END,
                            last_error = :last_error,
                            remote_verification = COALESCE(:remote_verification, remote_verification),
                            updated_at = :updated_at
                        WHERE slice_id = :slice_id
                          AND target_id = :target_id
                          AND status = :expected
                        """)
                .param("next", next.name())
                .param("last_error", lastError)
                .param("remote_verification", remoteVerification)
                .param("updated_at", now.toString())
                .param("slice_id", requireText(sliceId, "sliceId"))
                .param("target_id", requireText(targetId, "targetId"))
                .param("expected", expected.name())
                .update();
        if (affected == 1) {
            return find(sliceId, targetId).orElseThrow();
        }
        PublishRecord actual = find(sliceId, targetId)
                .orElseThrow(() -> new IllegalStateException("Publish ledger pair is missing: "
                        + sliceId + "/" + targetId));
        if (actual.status() == next && compatible(actual, lastError, remoteVerification)) {
            return actual;
        }
        throw new IllegalStateException("Publish ledger transition conflict for "
                + sliceId + "/" + targetId + ": expected " + expected
                + ", actual " + actual.status() + ", next " + next);
    }

    private void requireTransition(PublishStatus expected, PublishStatus next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        boolean allowed = switch (expected) {
            case PENDING -> next == PublishStatus.IN_PROGRESS
                    || next == PublishStatus.ABANDONED;
            case IN_PROGRESS -> next == PublishStatus.SUCCEEDED
                    || next == PublishStatus.FAILED
                    || next == PublishStatus.ABANDONED;
            case FAILED -> next == PublishStatus.IN_PROGRESS
                    || next == PublishStatus.ABANDONED;
            case SUCCEEDED, ABANDONED -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("Illegal publish ledger transition: " + expected + " -> " + next);
        }
        if (next == PublishStatus.FAILED && (expected == PublishStatus.IN_PROGRESS)) {
            return;
        }
        if (next == PublishStatus.ABANDONED || next == PublishStatus.SUCCEEDED
                || next == PublishStatus.IN_PROGRESS) {
            return;
        }
    }

    private static PublishRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PublishRecord(
                rs.getString("slice_id"),
                rs.getString("target_id"),
                rs.getString("profile"),
                rs.getString("slice_name"),
                rs.getString("manifest_sha256"),
                rs.getString("endpoint"),
                rs.getString("remote_path"),
                PublishStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("remote_verification"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private boolean sameBinding(PublishRecord left, PublishRecord right) {
        return left.profile().equals(right.profile())
                && left.sliceName().equals(right.sliceName())
                && left.manifestSha256().equals(right.manifestSha256())
                && left.endpoint().equals(right.endpoint())
                && left.remotePath().equals(right.remotePath());
    }

    private boolean compatible(PublishRecord actual, String lastError, String remoteVerification) {
        return (remoteVerification == null || remoteVerification.equals(actual.remoteVerification()))
                && (lastError == null || lastError.equals(actual.lastError()));
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
