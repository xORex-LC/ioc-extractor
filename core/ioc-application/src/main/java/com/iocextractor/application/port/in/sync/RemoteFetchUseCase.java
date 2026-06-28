package com.iocextractor.application.port.in.sync;

/** Inbound port for remote-to-inbox synchronization. */
public interface RemoteFetchUseCase {

    RemoteFetchResult fetch(RemoteFetchCommand command);
}
