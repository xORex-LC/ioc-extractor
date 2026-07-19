package com.iocextractor.adapter.in.cli;

import java.time.Instant;
import java.util.Optional;

/**
 * Immutable application identity embedded by the runnable Maven module.
 *
 * @param version product version; always present for a valid packaged application
 * @param commit full Git object ID when the build caller supplied one
 * @param builtAt Maven build timestamp when present
 */
public record ApplicationBuildInfo(
        String version,
        Optional<String> commit,
        Optional<Instant> builtAt) {

    public ApplicationBuildInfo {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Build version must not be blank");
        }
        version = version.trim();
        commit = commit == null ? Optional.empty() : commit;
        builtAt = builtAt == null ? Optional.empty() : builtAt;
    }
}
