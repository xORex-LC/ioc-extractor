package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;

/** Provider {@code id}: the assigned record id. */
public final class IdValueProvider implements ValueProvider {

    @Override
    public String provide(long id, Indicator indicator) {
        return Long.toString(id);
    }
}
