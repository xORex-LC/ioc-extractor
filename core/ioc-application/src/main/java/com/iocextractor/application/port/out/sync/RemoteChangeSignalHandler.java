package com.iocextractor.application.port.out.sync;

/**
 * Callback sink used by optional remote change signal adapters.
 *
 * <p>Signals are hints only. They carry no file facts and must be handled by running normal
 * source detection.</p>
 */
public interface RemoteChangeSignalHandler {

    /** Called when the remote source reports that something may have changed. */
    void signal();

    /** Called after a watch session is established or re-established. */
    void established();

    /** Called when the watch session enters reconnect/degraded state. */
    void failed(RuntimeException failure);
}
