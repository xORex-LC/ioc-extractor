package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/** Provider {@code match.host}: the materialized {@code host_match} code. */
public final class MatchHostValueProvider implements ValueProvider {

    @Override
    public String provide(long id, ClassifiedIndicator indicator) {
        return indicator.classification().match().hostMatch();
    }
}
