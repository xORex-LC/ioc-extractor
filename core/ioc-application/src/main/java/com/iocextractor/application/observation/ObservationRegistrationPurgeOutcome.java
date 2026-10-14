package com.iocextractor.application.observation;

/** Result of exact terminal-registration cleanup across a service/dataframe handshake. */
public enum ObservationRegistrationPurgeOutcome {
    PURGED,
    REFERENCED,
    MISSING
}
