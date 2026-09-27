package com.iocextractor.processing.mapping;

import com.iocextractor.processing.model.ClassifiedIndicator;

/** Provider {@code match.url}: the materialized {@code url_match} code. */
public final class MatchUrlValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return indicator.classification().match().urlMatch();
    }
}
