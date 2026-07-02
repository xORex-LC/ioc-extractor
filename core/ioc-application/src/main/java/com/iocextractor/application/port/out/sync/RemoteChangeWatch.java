package com.iocextractor.application.port.out.sync;

/**
 * Handle for an active optional remote change watch.
 *
 * <p>Closing is best-effort and does not expose checked transport exceptions to lifecycle code.</p>
 */
public interface RemoteChangeWatch extends AutoCloseable {

    @Override
    void close();
}
