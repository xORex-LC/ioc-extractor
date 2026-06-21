package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;

/** Provider {@code source.label}: the provenance label. */
public final class SourceLabelValueProvider implements ValueProvider {

    @Override
    public String provide(long id, Indicator indicator) {
        return indicator.source().label();
    }
}
