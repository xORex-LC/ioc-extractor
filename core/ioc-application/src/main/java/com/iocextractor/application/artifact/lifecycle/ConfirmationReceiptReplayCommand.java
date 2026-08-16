package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/** Request to confirm one newly delivered source from a retained receipt. */
public record ConfirmationReceiptReplayCommand(LifecycleWriteContext writeContext) {

    public ConfirmationReceiptReplayCommand {
        Objects.requireNonNull(writeContext, "writeContext");
    }
}
