package com.iocextractor.application.artifact;

/** Public and lifecycle outcome of one canonical record mutation. */
public enum CanonicalRecordMutationKind {
    INSERTED,
    RESTARTED,
    UPDATED,
    CLEARED,
    TTL_CONFIRMED,
    NO_OP
}
