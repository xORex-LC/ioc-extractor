package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;

import java.util.Objects;

/** Request to reserve claim order and begin managed source ownership. */
public record AdmitDataframeImportCommand(ImportClaimReservation reservation) {

    /** Requires a complete durable reservation request. */
    public AdmitDataframeImportCommand {
        Objects.requireNonNull(reservation, "reservation");
    }
}
