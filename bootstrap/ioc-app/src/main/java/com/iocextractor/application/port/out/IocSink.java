package com.iocextractor.application.port.out;

import com.iocextractor.domain.model.Indicator;

import java.util.List;

/**
 * Secondary (driven) port: one output artifact. Each sink decides which
 * indicator types it accepts and writes only those, so adding an artifact
 * (hash list, STIX export, DB, …) is a new adapter, not a core change (OCP).
 */
public interface IocSink {

    /** Stable artifact name, used in the run summary and config. */
    String name();

    /**
     * Persist the indicators this sink accepts.
     *
     * @return number of rows actually written
     */
    int write(List<Indicator> indicators);
}
