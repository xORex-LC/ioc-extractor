package com.iocextractor.application.port.in.sync;

/** Command for one remote fetch cycle. */
public record RemoteFetchCommand(boolean dryRun) {
}
