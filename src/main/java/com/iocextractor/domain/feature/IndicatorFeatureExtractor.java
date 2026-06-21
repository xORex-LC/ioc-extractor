package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;

/**
 * Derives {@link IndicatorFeatures} from an {@link Indicator}: normalizes the
 * value, parses scheme/authority/path/query and classifies the host.
 */
public interface IndicatorFeatureExtractor {

    IndicatorFeatures extract(Indicator indicator);
}
