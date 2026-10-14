package com.iocextractor.application.port.out.observation;

import com.iocextractor.application.observation.ObservationRegistrationStatus;

/** Reads safe aggregate status without exposing occurrence identifiers or IOC data. */
public interface ObservationRegistrationStatusReader {

    ObservationRegistrationStatus status();
}
