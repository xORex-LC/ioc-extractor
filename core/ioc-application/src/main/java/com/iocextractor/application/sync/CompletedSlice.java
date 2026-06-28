package com.iocextractor.application.sync;

import com.iocextractor.application.export.SliceManifest;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Verified local export slice that is ready to be published to a remote target.
 */
public record CompletedSlice(String sliceId,
                             String profile,
                             String sliceName,
                             String manifestSha256,
                             Path directory,
                             SliceManifest manifest) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public CompletedSlice {
        sliceId = requireText(sliceId, "sliceId");
        profile = requireSegment(profile, "profile");
        sliceName = requireSegment(sliceName, "sliceName");
        manifestSha256 = requireSha256(manifestSha256, "manifestSha256");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (!sliceId.equals(manifest.sliceId())) {
            throw new IllegalArgumentException("Completed slice id must match manifest");
        }
        if (!profile.equals(manifest.profile())) {
            throw new IllegalArgumentException("Completed slice profile must match manifest");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireSegment(String value, String field) {
        String segment = requireText(value, field);
        if (segment.contains("/") || segment.contains("\\") || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException(field + " must be one safe path segment");
        }
        return segment;
    }

    private static String requireSha256(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lower-case SHA-256 value");
        }
        return value;
    }
}
