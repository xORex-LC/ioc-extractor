package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stateless transport-neutral remote file operations.
 *
 * <p>Implementations hide sessions, reconnects and library-specific handles. The only multi-file
 * write operation exposed to the application is {@link #publishAtomically(PublishAtomicallyRequest)}.
 */
public interface FileTransport {

    /** Lists remote regular files under a transport path. */
    List<RemoteObject> list(String endpoint, String remotePath);

    /** Returns metadata for one remote regular file when it exists. */
    Optional<RemoteObject> stat(String endpoint, String remotePath);

    /** Streams a remote regular file into a local path supplied by the caller. */
    void get(String endpoint, String remotePath, Path localDestination);

    /** Deletes a remote path for opt-in retention/cleanup flows. */
    void delete(String endpoint, String remotePath);

    /** Publishes one local directory as an atomic remote unit, writing the commit marker last. */
    PublishReceipt publishAtomically(PublishAtomicallyRequest request);
}
