package com.iocextractor.domain.extract;

import java.util.List;

/**
 * Detects indicators in a refanged document. Implementations are responsible
 * for resolving overlaps by type priority (e.g. a URL host is not re-emitted
 * as a bare domain).
 */
public interface IndicatorExtractor {

    List<RawIndicator> extract(String text);
}
