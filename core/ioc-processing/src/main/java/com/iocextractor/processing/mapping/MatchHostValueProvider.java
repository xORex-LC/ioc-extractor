package com.iocextractor.processing.mapping;

import com.iocextractor.processing.model.ClassifiedIndicator;

/** Provider {@code match.host}: the materialized {@code host_match} code. */
public final class MatchHostValueProvider implements ValueProvider {

    @Override
    public String provide(ClassifiedIndicator indicator) {
        return indicator.classification().match().hostMatch();
    }
}
