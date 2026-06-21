package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.model.Indicator;

/** Provider {@code match.host}: the {@code host_match} code from the domain {@link MatchPolicy}. */
public final class MatchHostValueProvider implements ValueProvider {

    private final MatchPolicy matchPolicy;

    public MatchHostValueProvider(MatchPolicy matchPolicy) {
        this.matchPolicy = matchPolicy;
    }

    @Override
    public String provide(long id, Indicator indicator) {
        return matchPolicy.classify(indicator).hostMatch();
    }
}
