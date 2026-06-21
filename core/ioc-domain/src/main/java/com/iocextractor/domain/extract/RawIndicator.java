package com.iocextractor.domain.extract;

import com.iocextractor.domain.model.IndicatorType;

/**
 * An indicator detected in text, before provenance is attached.
 *
 * @param value    matched (already refanged) text
 * @param type     detected indicator type
 * @param position character offset in the document, used for source attribution
 */
public record RawIndicator(String value, IndicatorType type, int position) {
}
