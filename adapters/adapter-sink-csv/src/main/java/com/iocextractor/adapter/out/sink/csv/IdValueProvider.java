package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/** Provider {@code id}: the assigned record id. */
public final class IdValueProvider implements ValueProvider {

    @Override
    public String provide(long id, ClassifiedIndicator indicator) {
        return Long.toString(id);
    }
}
