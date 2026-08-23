package com.iocextractor.application.port.in.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptReplayCommand;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptReplayResult;

import java.util.Optional;

/** Bounded optimization that falls back by returning empty when proof is unavailable. */
@FunctionalInterface
public interface ReplayConfirmationReceiptUseCase {

    Optional<ConfirmationReceiptReplayResult> replay(ConfirmationReceiptReplayCommand command);
}
