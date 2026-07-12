package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.refang.RefangOutcome;

import java.util.Objects;

/**
 * Text after defanged IOC replacements were applied.
 *
 * @param outcome refanged text and applied-rule decisions
 */
public record RefangedText(RefangOutcome outcome) {

    public RefangedText {
        Objects.requireNonNull(outcome, "outcome");
    }

    /** Returns the functional text payload. */
    public String text() {
        return outcome.text();
    }
}
