package com.iocextractor.application.port.in.dataframeimport;

/** Driving port for a coalesced, ledger-authoritative global import lane. */
public interface ProcessNextDataframeImportUseCase {

    /**
     * Attempts to advance only the minimum nonterminal sequence.
     *
     * @return whether a due head was advanced
     */
    ProcessNextDataframeImportResult processNext();
}
