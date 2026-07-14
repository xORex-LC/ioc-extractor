package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/**
 * Supplies a raw cell value for a configurable column ({@code from: <key>}).
 * A thin, reusable unit; new column source = new provider registered by key.
 *
 * <p>An implementation may throw {@link MappingValueException} only for an
 * expected input-dependent rejection of the current element. Configuration,
 * registry and programming defects must use their natural exception type.
 */
public interface ValueProvider {

    /**
     * Produces one raw cell value.
     *
     * @param indicator materialized input decision
     * @return cell value, or {@code null} for CSV NULL/deferred slot
     * @throws MappingValueException when this particular input cannot be mapped
     */
    String provide(ClassifiedIndicator indicator);
}
