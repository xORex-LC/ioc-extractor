package com.iocextractor.application.port.in.sync;

import java.util.Optional;

/** Command for one artifact publish/reconcile cycle with optional profile and target filters. */
public record ArtifactPublishCommand(Optional<String> profile,
                                     Optional<String> target,
                                     boolean dryRun) {

    public ArtifactPublishCommand {
        profile = profile == null ? Optional.empty() : profile;
        target = target == null ? Optional.empty() : target;
        profile.ifPresent(value -> requireText(value, "profile"));
        target.ifPresent(value -> requireText(value, "target"));
    }

    /** Creates a profile-filtered command spanning all matching targets. */
    public ArtifactPublishCommand(Optional<String> profile, boolean dryRun) {
        this(profile, Optional.empty(), dryRun);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
