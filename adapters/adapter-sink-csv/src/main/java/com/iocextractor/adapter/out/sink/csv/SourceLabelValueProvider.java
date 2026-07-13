package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/** Provider {@code source.label}: the provenance label. */
public final class SourceLabelValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return indicator.indicator().source().label();
    }
}
