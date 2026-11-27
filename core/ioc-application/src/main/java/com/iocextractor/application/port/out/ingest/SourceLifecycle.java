package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.ArchivedSourceUnit;
import com.iocextractor.application.ingest.ClaimedSource;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Filesystem-agnostic source ownership lifecycle.
 */
public interface SourceLifecycle {

    SourceUnit claim(Path source, ObservationId observationId, SourceKey key, Instant detectedAt);

    /** Claims transport ownership before hashing to preserve the registered occurrence identity. */
    default ClaimedSource claimBeforeHash(Path source,
                                          ObservationId observationId,
                                          Instant detectedAt) {
        throw new UnsupportedOperationException("Pre-hash source claim is not supported");
    }

    /** Returns the private token-only path that a pre-hash claim will own. */
    default Path prehashClaimPath(Path source, ObservationId observationId) {
        throw new UnsupportedOperationException("Pre-hash source claim is not supported");
    }

    /**
     * Detaches the claimed bytes from producer-held file descriptors before hashing.
     * The copy must never exceed the durably admitted source byte allowance.
     * Implementations that already provide immutable ownership may return {@code claimed}.
     */
    default ClaimedSource sealClaim(ClaimedSource claimed, long maximumBytes) {
        return Objects.requireNonNull(claimed, "claimed");
    }

    /** Adopts an already-owned private source after its content key is known. */
    default SourceUnit adoptClaim(ClaimedSource claimed, SourceKey key) {
        Objects.requireNonNull(claimed, "claimed");
        return new SourceUnit(claimed.observationId(), Objects.requireNonNull(key, "key"),
                claimed.originalPath(), claimed.processingPath(), claimed.detectedAt());
    }

    default SourceUnit claim(Path source, SourceKey key, Instant detectedAt) {
        return claim(source, ObservationId.legacy(key.value()), key, detectedAt);
    }

    Path archive(SourceUnit unit);

    /**
     * Resolves the exact archive destination for a sealed document occurrence.
     *
     * @param unit the occurrence and its owned sealed source, including after a move
     * @return the destination, without performing filesystem operations
     * @throws UnsupportedOperationException if recoverable archiving is unavailable
     */
    default Path archivePath(SourceUnit unit) {
        throw new UnsupportedOperationException("Recoverable document archive is not supported");
    }

    /**
     * Moves a sealed document to its archive and verifies the original file identity.
     * A retry after a successful move verifies the sole archived file against the same
     * evidence. Ambiguous or conflicting evidence prevents completion.
     *
     * @param unit the occurrence and its owned sealed source
     * @param expected the sealed file evidence captured before the first move
     * @return the verified destination after durable archive publication
     * @throws UnsupportedOperationException if recoverable archiving is unavailable
     */
    default Path archive(SourceUnit unit, DocumentCandidateEvidence expected) {
        throw new UnsupportedOperationException("Recoverable document archive is not supported");
    }

    Path archive(ArchivedSourceUnit source);

    Path archiveDuplicate(Path source, SourceKey key);

    Path fail(SourceUnit unit, String reason);

    Path fail(ArchivedSourceUnit source, String reason);

    List<ArchivedSourceUnit> findProcessingSources();
}
