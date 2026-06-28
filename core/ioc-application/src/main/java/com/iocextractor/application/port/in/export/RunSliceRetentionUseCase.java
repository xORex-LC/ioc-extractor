package com.iocextractor.application.port.in.export;

/** Primary port for one delivery-aware slice retention sweep. */
@FunctionalInterface
public interface RunSliceRetentionUseCase {

    SliceRetentionResult run();
}
