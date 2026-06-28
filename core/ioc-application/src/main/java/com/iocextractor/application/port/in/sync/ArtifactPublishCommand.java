package com.iocextractor.application.port.in.sync;

import java.util.Optional;

/** Command for one artifact publish/reconcile cycle with optional profile and target filters. */
public record ArtifactPublishCommand(Optional<String> profile,
                                     Optional<String> target,
                                     Optional<String> endpoint,
                                     boolean dryRun) {

    public ArtifactPublishCommand {
        profile = profile == null ? Optional.empty() : profile;
        target = target == null ? Optional.empty() : target;
        endpoint = endpoint == null ? Optional.empty() : endpoint;
        profile.ifPresent(value -> requireText(value, "profile"));
        target.ifPresent(value -> requireText(value, "target"));
        endpoint.ifPresent(value -> requireText(value, "endpoint"));
    }

    /** Creates a profile-filtered command spanning all matching targets. */
    public ArtifactPublishCommand(Optional<String> profile, boolean dryRun) {
        this(profile, Optional.empty(), Optional.empty(), dryRun);
    }

    /** Creates a profile/target-filtered command spanning any matching endpoint. */
    public ArtifactPublishCommand(Optional<String> profile,
                                  Optional<String> target,
                                  boolean dryRun) {
        this(profile, target, Optional.empty(), dryRun);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
