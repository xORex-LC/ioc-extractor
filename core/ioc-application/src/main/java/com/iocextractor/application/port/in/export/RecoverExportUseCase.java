package com.iocextractor.application.port.in.export;

/** Primary port for forward recovery of incomplete local export runs. */
public interface RecoverExportUseCase {

    /**
     * Inspects durable ledger/filesystem state and advances every recoverable run.
     *
     * @return number of incomplete runs examined
     */
    int recoverIncomplete();
}
