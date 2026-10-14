package com.iocextractor.processing.mapping;

import com.iocextractor.processing.model.ClassifiedIndicator;

/** Provider {@code value}: the indicator value (mask / hash). */
public final class IndicatorValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return indicator.indicator().value();
    }
}
