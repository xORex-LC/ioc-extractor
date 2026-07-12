package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

/** Provider {@code match.url}: the materialized {@code url_match} code. */
public final class MatchUrlValueProvider implements ValueProvider {

    @Override
    public String provide(long id, ClassifiedIndicator indicator) {
        return indicator.classification().match().urlMatch();
    }
}
