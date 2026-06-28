package com.iocextractor.application.sync;

/**
 * Transport-neutral remote I/O failure taxonomy.
 *
 * <p>Adapters translate library-specific exceptions into these stable kinds so
 * application use cases can make retry and diagnostic decisions without seeing SMB/SFTP/S3 types.
 */
public enum RemoteErrorKind {
    UNREACHABLE(RemoteErrorDisposition.RETRY_LATER),
    AUTH_FAILED(RemoteErrorDisposition.FAIL),
    PERMISSION_DENIED(RemoteErrorDisposition.FAIL),
    NOT_FOUND(RemoteErrorDisposition.FAIL),
    TRANSIENT(RemoteErrorDisposition.RETRY_NOW);

    private final RemoteErrorDisposition disposition;

    RemoteErrorKind(RemoteErrorDisposition disposition) {
        this.disposition = disposition;
    }

    /** Returns the code-owned retry disposition for this error kind. */
    public RemoteErrorDisposition disposition() {
        return disposition;
    }
}
