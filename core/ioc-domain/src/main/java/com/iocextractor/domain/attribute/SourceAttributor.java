package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.RawIndicator;
import java.util.List;

/**
 * Attaches provenance to raw indicators based on where they appear in the
 * document relative to section-header markers (e.g. "БИБ-…", "Письмо ФСТЭК …").
 */
public interface SourceAttributor {

    /**
     * Attributes raw indicators and exposes marker-selection decisions.
     * Encounter order is preserved; positions need not be sorted and equal
     * marker/indicator positions are inclusive.
     */
    AttributionOutcome attribute(String text, List<RawIndicator> indicators);
}
