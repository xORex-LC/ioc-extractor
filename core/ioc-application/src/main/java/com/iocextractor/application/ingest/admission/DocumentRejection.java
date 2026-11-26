package com.iocextractor.application.ingest.admission;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Evidence and destination for one durable physical rejection. The admission supplies
 * the occurrence and, for a linked document, the content key. Paths are revalidated
 * against the private filesystem layout before every execution.
 */
public record DocumentRejection(Path source, Path target, DocumentCandidateEvidence evidence, Code code) {

    public DocumentRejection {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(code, "code");
        if (!source.isAbsolute() || !target.isAbsolute() || source.equals(target)
                || evidence.fileKey().isEmpty()) {
            throw new IllegalArgumentException("Rejection requires distinct absolute paths and stable file identity");
        }
    }

    /** Stable machine codes safe for diagnostics, the source ledger and the sidecar. */
    public enum Code {
        SOURCE_TOO_LARGE,
        SOURCE_HASH_FAILED,
        DOCUMENT_PREPARATION_FAILED
    }
}
