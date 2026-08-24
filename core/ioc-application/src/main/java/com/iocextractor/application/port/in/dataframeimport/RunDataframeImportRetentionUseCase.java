package com.iocextractor.application.port.in.dataframeimport;

/** Driving boundary for independently scheduled bounded import retention. */
public interface RunDataframeImportRetentionUseCase {

    /** Removes at most {@code limit} expired terminal units and their safe evidence. */
    int retain(int limit);
}
