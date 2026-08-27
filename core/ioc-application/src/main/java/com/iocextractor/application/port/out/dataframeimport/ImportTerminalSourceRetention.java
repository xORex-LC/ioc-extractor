package com.iocextractor.application.port.out.dataframeimport;

/** Driven port for exact, idempotent cleanup of a transport-managed source remnant. */
@FunctionalInterface
public interface ImportTerminalSourceRetention {

    /** Purges only the managed terminal object identified by the path-free command. */
    void purge(PurgeImportTerminalSourceCommand command);
}
