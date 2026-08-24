package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;

/** Startup and shutdown boundary for the managed-import runtime. */
interface DataframeImportRuntimeLifecycle extends AutoCloseable {

    RecoverDataframeImportsResult recoverBeforeIntake();

    void start();

    boolean recoveryComplete();

    @Override
    void close();
}
