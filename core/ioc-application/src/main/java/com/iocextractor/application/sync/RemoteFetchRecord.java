package com.iocextractor.application.sync;

import java.time.Instant;
import java.util.Objects;

/** Durable fetch-ledger row for one remote object identity. */
public record RemoteFetchRecord(RemoteObjectIdentity identity,
                                RemoteFetchStatus status,
                                String localPath,
                                int attempts,
                                String lastError,
                                Instant fetchedAt,
                                Instant updatedAt) {

    public RemoteFetchRecord {
        identity = Objects.requireNonNull(identity, "identity");
        status = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if (status == RemoteFetchStatus.FETCHED && (localPath == null || localPath.isBlank())) {
            throw new IllegalArgumentException("fetched record requires localPath");
        }
        if (status == RemoteFetchStatus.FETCHED && fetchedAt == null) {
            throw new IllegalArgumentException("fetched record requires fetchedAt");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
