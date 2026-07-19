package com.iocextractor.adapter.in.cli;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads the Spring Boot Maven plugin's embedded build identity without starting Spring.
 */
public final class ApplicationBuildInfoReader {

    static final String BUILD_INFO_RESOURCE = "META-INF/build-info.properties";
    private static final Pattern FULL_GIT_OBJECT_ID = Pattern.compile("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})");

    private final ClassLoader classLoader;

    /** Creates a reader for the application class path. */
    public ApplicationBuildInfoReader() {
        this(ApplicationBuildInfoReader.class.getClassLoader());
    }

    ApplicationBuildInfoReader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Reads and validates embedded build metadata.
     *
     * @return immutable application build identity
     * @throws IllegalStateException when required metadata is absent or malformed
     */
    public ApplicationBuildInfo read() {
        Properties properties = new Properties();
        try (InputStream input = classLoader.getResourceAsStream(BUILD_INFO_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Embedded build identity is unavailable: " + BUILD_INFO_RESOURCE);
            }
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read embedded build identity: " + BUILD_INFO_RESOURCE, ex);
        }
        return from(properties);
    }

    static ApplicationBuildInfo from(Properties properties) {
        String version = required(properties, "build.version");
        Optional<String> commit = optional(properties, "build.commit");
        commit.ifPresent(value -> {
            if (!FULL_GIT_OBJECT_ID.matcher(value).matches()) {
                throw new IllegalStateException("Embedded build.commit must be a full hexadecimal Git object ID");
            }
        });
        Optional<Instant> builtAt = optional(properties, "build.time").map(ApplicationBuildInfoReader::parseTime);
        return new ApplicationBuildInfo(version, commit, builtAt);
    }

    private static String required(Properties properties, String key) {
        return optional(properties, key)
                .orElseThrow(() -> new IllegalStateException("Required embedded build property is missing: " + key));
    }

    private static Optional<String> optional(Properties properties, String key) {
        return Optional.ofNullable(properties.getProperty(key))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    private static Instant parseTime(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ex) {
            throw new IllegalStateException("Embedded build.time is not an ISO-8601 instant: " + value, ex);
        }
    }
}
