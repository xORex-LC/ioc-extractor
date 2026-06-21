package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;

/**
 * Supplies a raw cell value for a configurable column ({@code from: <key>}).
 * A thin, reusable unit; new column source = new provider registered by key.
 */
public interface ValueProvider {

    String provide(long id, Indicator indicator);
}
