package com.iocextractor.application.pipeline.payload;

import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/** Pure application decision for one within-batch de-duplication candidate. */
public record DeduplicationDecision(Indicator indicator, boolean retained) {

    public DeduplicationDecision {
        Objects.requireNonNull(indicator, "indicator");
    }
}
