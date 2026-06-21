package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.IndicatorType;

import java.util.List;

/**
 * Declarative spec for one output column, decoupled from the configuration
 * binding type (so this adapter does not depend on {@code bootstrap}).
 *
 * @param name      column name (header)
 * @param from      value provider key, or {@code "const"}
 * @param value     literal value when {@code from == "const"} (may be null ⇒ NULL)
 * @param whenType  fill only for this indicator type, else NULL (may be null)
 * @param transform ordered transform specs ({@code name} or {@code name:arg})
 */
public record ColumnSpec(
        String name,
        String from,
        String value,
        IndicatorType whenType,
        List<String> transform) {
}
