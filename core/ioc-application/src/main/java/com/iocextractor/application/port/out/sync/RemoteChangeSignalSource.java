package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.RemoteWatchTarget;

/**
 * Optional push capability for transports that can report remote directory changes.
 *
 * <p>This port is deliberately separate from {@link FileTransport}: push is not a universal
 * file-transport operation, and the application still treats every signal as a hint to re-run
 * ordinary detection.</p>
 */
public interface RemoteChangeSignalSource {

    /** Starts watching one transport-neutral remote directory target. */
    RemoteChangeWatch watch(RemoteWatchTarget target, RemoteChangeSignalHandler handler);
}
