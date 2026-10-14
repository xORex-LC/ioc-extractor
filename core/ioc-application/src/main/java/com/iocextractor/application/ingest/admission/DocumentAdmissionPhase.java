package com.iocextractor.application.ingest.admission;

/** Forward-only checkpoints around document registration and private claim ownership. */
public enum DocumentAdmissionPhase {
    RESERVED,
    ORDERED,
    CLAIMED,
    LINKED,
    TERMINAL
}
