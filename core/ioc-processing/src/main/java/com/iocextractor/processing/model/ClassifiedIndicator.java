package com.iocextractor.processing.model;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.model.Indicator;

import java.util.Objects;

/** Indicator paired with its single materialized classification decision. */
public record ClassifiedIndicator(Indicator indicator,
                                  ClassificationDecision classification) {

    public ClassifiedIndicator {
        Objects.requireNonNull(indicator, "indicator");
        Objects.requireNonNull(classification, "classification");
    }
}
