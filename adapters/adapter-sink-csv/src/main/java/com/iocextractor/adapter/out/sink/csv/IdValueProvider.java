package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/** Provider {@code id}: a deferred slot materialized only immediately before commit. */
public final class IdValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return null;
    }
}
