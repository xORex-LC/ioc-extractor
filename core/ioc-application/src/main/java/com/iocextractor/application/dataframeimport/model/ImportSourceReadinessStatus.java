package com.iocextractor.application.dataframeimport.model;

/** Admission disposition of one configured managed-import source. */
public enum ImportSourceReadinessStatus {
    READY,
    TRANSIENTLY_UNAVAILABLE,
    INCOMPATIBLE
}
