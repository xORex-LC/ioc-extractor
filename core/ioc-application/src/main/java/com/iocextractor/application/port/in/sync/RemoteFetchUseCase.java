package com.iocextractor.application.port.in.sync;

/** Inbound port for remote-to-inbox synchronization. */
public interface RemoteFetchUseCase {

    /** Reconcile/manual path that detects remote work before executing it. */
    RemoteFetchResult fetch(RemoteFetchCommand command);

    /** Fast-path execution for work detected by a remote source monitor. */
    default RemoteFetchResult fetch(FetchRemoteObjectsCommand command) {
        throw new UnsupportedOperationException("detected remote object fetch is not supported");
    }
}
