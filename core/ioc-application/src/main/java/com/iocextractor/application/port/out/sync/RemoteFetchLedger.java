package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.RemoteFetchRecord;
import com.iocextractor.application.sync.RemoteObjectIdentity;

import java.time.Instant;
import java.util.Optional;

/** Durable idempotency ledger for read-only remote fetch sources. */
public interface RemoteFetchLedger {

    Optional<RemoteFetchRecord> find(RemoteObjectIdentity identity);

    RemoteFetchRecord markFetched(RemoteObjectIdentity identity, String localPath, Instant fetchedAt);

    RemoteFetchRecord markSkipped(RemoteObjectIdentity identity, String reason, Instant skippedAt);

    RemoteFetchRecord markFailed(RemoteObjectIdentity identity, String reason, Instant failedAt);
}
