package com.iocextractor.application.port.out;

import com.iocextractor.domain.model.Indicator;

/**
 * Secondary (driven) port: existence checks against the current "storage".
 * Today backed by the existing CSV artifact; the seam lets a real datastore
 * replace it later without touching the core.
 */
public interface LookupRepository {

    /** True if this indicator is already present in the backing artifact. */
    boolean contains(Indicator indicator);
}
