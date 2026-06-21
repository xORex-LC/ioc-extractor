package com.iocextractor.application.pipeline.payload;

import java.util.Objects;

/**
 * Source text read from the inbound document.
 *
 * @param text source text
 */
public record SourceText(String text) {

    public SourceText {
        Objects.requireNonNull(text, "text");
    }
}
