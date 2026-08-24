package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.util.function.Consumer;

/** Optional latency-only change signal source for managed import detection. */
public interface ImportChangeSignalSource extends AutoCloseable {

    /** Starts signals; every signal means only "perform a complete listing". */
    void start(Consumer<ImportSourceId> signalConsumer);

    /** Stops the optional hint source. */
    @Override
    void close();
}
