package com.iocextractor.domain.attribute;

import java.util.Objects;

/**
 * Normalized source-section marker discovered in document text.
 *
 * @param position character offset
 * @param label normalized source label
 */
public record SourceMarker(int position, String label) {

    public SourceMarker {
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        Objects.requireNonNull(label, "label");
    }
}
