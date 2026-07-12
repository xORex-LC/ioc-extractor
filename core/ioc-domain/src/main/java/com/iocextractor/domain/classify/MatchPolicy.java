package com.iocextractor.domain.classify;

import com.iocextractor.domain.model.Indicator;

/**
 * Decides the {@code url_match}/{@code host_match} codes for a network indicator.
 * Pluggable so the matching convention can change via configuration, not code.
 */
public interface MatchPolicy {

    /** Computes and returns the complete classification decision once. */
    ClassificationDecision classify(Indicator indicator);
}
