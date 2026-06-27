package com.iocextractor.application.port.in.export;

import java.util.Objects;

/** Selects one configured export profile for an on-demand run. */
public record ExportArtifactsCommand(String profile) {

    public ExportArtifactsCommand {
        Objects.requireNonNull(profile, "profile");
        if (profile.isBlank()) {
            throw new IllegalArgumentException("Export profile must not be blank");
        }
    }
}
