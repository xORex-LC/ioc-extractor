package com.iocextractor.application.port.out.export;

/**
 * Cross-process exclusion boundary shared by export formation and crash recovery.
 *
 * <p>The guard complements durable DB single-flight: it prevents a second live process from
 * interpreting another process's active staging directory as crash evidence.
 */
public interface ExportOperationGuard {

    /** Acquires exclusive ownership or fails without waiting. */
    Lease acquire();

    /** Exclusive ownership token released at the end of one recovery/formation operation. */
    interface Lease extends AutoCloseable {

        @Override
        void close();
    }
}
