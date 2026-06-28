package com.iocextractor.application.port.in.sync;

import java.util.Optional;

/** Command for one artifact publish/reconcile cycle. */
public record ArtifactPublishCommand(Optional<String> profile, boolean dryRun) {

    public ArtifactPublishCommand {
        profile = profile == null ? Optional.empty() : profile;
        profile.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("profile must not be blank");
            }
        });
    }
}
