package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/** Provider {@code value}: the indicator value (mask / hash). */
public final class IndicatorValueProvider implements ValueProvider {

    @Override
    public String provide(long id, ClassifiedIndicator indicator) {
        return indicator.indicator().value();
    }
}
