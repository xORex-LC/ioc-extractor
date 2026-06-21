package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;

import java.util.List;

/**
 * Attaches provenance to raw indicators based on where they appear in the
 * document relative to section-header markers (e.g. "БИБ-…", "Письмо ФСТЭК …").
 */
public interface SourceAttributor {

    List<Indicator> attribute(String text, List<RawIndicator> indicators);
}
