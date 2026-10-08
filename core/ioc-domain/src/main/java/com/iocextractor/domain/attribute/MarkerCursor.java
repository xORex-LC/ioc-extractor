package com.iocextractor.domain.attribute;

/** Selected non-overlapping markers in absolute UTF-16 position order. */
public interface MarkerCursor {
    boolean next();
    SourceMarker value();
}
