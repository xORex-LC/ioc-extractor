package com.iocextractor.domain.model;

/**
 * Broad family an indicator belongs to. Used to route indicators to the right
 * output artifact (network masks vs. file-hash list) without hard-coding types.
 */
public enum IndicatorCategory {
    NETWORK,
    FILE
}
