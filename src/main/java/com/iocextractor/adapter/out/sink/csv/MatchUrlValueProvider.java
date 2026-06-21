package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.model.Indicator;

/** Provider {@code match.url}: the {@code url_match} code from the domain {@link MatchPolicy}. */
public final class MatchUrlValueProvider implements ValueProvider {

    private final MatchPolicy matchPolicy;

    public MatchUrlValueProvider(MatchPolicy matchPolicy) {
        this.matchPolicy = matchPolicy;
    }

    @Override
    public String provide(long id, Indicator indicator) {
        return matchPolicy.classify(indicator).urlMatch();
    }
}
