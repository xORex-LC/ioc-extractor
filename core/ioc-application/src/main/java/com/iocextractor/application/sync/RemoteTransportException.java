package com.iocextractor.application.sync;

import java.util.Objects;

/**
 * Transport-neutral exception raised by remote file adapters.
 */
public final class RemoteTransportException extends RuntimeException {

    private final RemoteErrorKind kind;

    public RemoteTransportException(RemoteErrorKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public RemoteTransportException(RemoteErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Returns the stable remote error kind. */
    public RemoteErrorKind kind() {
        return kind;
    }
}
