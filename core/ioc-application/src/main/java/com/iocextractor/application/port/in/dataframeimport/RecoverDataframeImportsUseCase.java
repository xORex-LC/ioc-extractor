package com.iocextractor.application.port.in.dataframeimport;

/** Driving port for ledger/snapshot/stage/receipt forward reconciliation. */
public interface RecoverDataframeImportsUseCase {

    /**
     * Reconciles bounded durable evidence before intake or from a periodic backstop.
     *
     * @param limit maximum delivery records to examine
     * @return safe recovery summary
     */
    RecoverDataframeImportsResult recover(int limit);
}
