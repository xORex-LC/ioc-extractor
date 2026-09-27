package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.processing.mapping.ValueProvider;

/** Provider {@code id}: a deferred slot materialized only immediately before commit. */
public final class IdValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return null;
    }
}
