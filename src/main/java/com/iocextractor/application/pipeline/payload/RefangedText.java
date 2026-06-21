package com.iocextractor.application.pipeline.payload;

import java.util.Objects;

/**
 * Text after defanged IOC replacements were applied.
 *
 * @param text refanged text
 */
public record RefangedText(String text) {

    public RefangedText {
        Objects.requireNonNull(text, "text");
    }
}
