package com.iocextractor.application.export;

/** Filesystem state observed while recovering one immutable slice. */
public enum SliceInspectionState {
    MISSING,
    PARTIAL,
    RECOVERABLE,
    STAGED,
    AVAILABLE,
    CORRUPT,
    CONFLICT
}
