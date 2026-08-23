package com.iocextractor.application.classification;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.MaskMatch;

import java.util.List;
import java.util.Objects;

/**
 * Classifies one supported indicator without coupling callers to the extraction pipeline.
 *
 * <p>Network indicators use the configured domain policy. File indicators receive the
 * neutral decision required by artifact mapping, while unsupported future categories are
 * rejected explicitly.</p>
 */
public final class IndicatorClassifier {

    private final MatchPolicy networkPolicy;

    /** Creates a classifier backed by the configured network match policy. */
    public IndicatorClassifier(MatchPolicy networkPolicy) {
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
    }

    /** Returns whether the current application understands this indicator category. */
    public boolean supports(Indicator indicator) {
        Objects.requireNonNull(indicator, "indicator");
        return indicator.type().category() == IndicatorCategory.NETWORK
                || indicator.type().category() == IndicatorCategory.FILE;
    }

    /** Materializes the one classification decision used by downstream mapping. */
    public ClassificationDecision classify(Indicator indicator) {
        Objects.requireNonNull(indicator, "indicator");
        if (indicator.type().category() == IndicatorCategory.NETWORK) {
            return networkPolicy.classify(indicator);
        }
        if (indicator.type().category() == IndicatorCategory.FILE) {
            return neutralFileDecision(indicator);
        }
        throw new IllegalArgumentException("Unsupported indicator category: " + indicator.type().category());
    }

    /** Returns a value-free classifier name suitable for diagnostics. */
    public String name() {
        return networkPolicy.getClass().getSimpleName();
    }

    private static ClassificationDecision neutralFileDecision(Indicator indicator) {
        var neutralFeatures = new IndicatorFeatures(
                indicator.value(), indicator.value(), false, false, false, HostKind.UNKNOWN);
        return new ClassificationDecision(neutralFeatures, -1, List.of(), new MaskMatch(null, null));
    }
}
