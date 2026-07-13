package com.iocextractor.application.observability;

/** Processing step represented by one structured per-item trace decision. */
public enum PipelineDecisionKind {
    REFANG,
    EXTRACTION,
    ATTRIBUTION,
    DEDUPLICATION,
    CLASSIFICATION,
    ROUTING
}
