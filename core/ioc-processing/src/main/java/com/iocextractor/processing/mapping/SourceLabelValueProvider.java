package com.iocextractor.processing.mapping;

import com.iocextractor.processing.model.ClassifiedIndicator;

/** Provider {@code source.label}: the provenance label. */
public final class SourceLabelValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return indicator.indicator().source().label();
    }
}
