package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/**
 * Supplies a raw cell value for a configurable column ({@code from: <key>}).
 * A thin, reusable unit; new column source = new provider registered by key.
 */
public interface ValueProvider {

    String provide(long id, ClassifiedIndicator indicator);
}
