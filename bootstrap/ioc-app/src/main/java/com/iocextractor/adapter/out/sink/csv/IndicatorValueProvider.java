package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;

/** Provider {@code value}: the indicator value (mask / hash). */
public final class IndicatorValueProvider implements ValueProvider {

    @Override
    public String provide(long id, Indicator indicator) {
        return indicator.value();
    }
}
