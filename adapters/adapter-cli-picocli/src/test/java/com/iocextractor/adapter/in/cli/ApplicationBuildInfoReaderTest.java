package com.iocextractor.adapter.in.cli;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationBuildInfoReaderTest {

    @Test
    void reads_required_version_and_optional_metadata() {
        var properties = propertiesWithVersion();
        properties.setProperty("build.commit", "0123456789abcdef0123456789abcdef01234567");
        properties.setProperty("build.time", "2026-07-19T10:30:00Z");

        ApplicationBuildInfo result = ApplicationBuildInfoReader.from(properties);

        assertThat(result.version()).isEqualTo("9.8.7");
        assertThat(result.commit()).contains("0123456789abcdef0123456789abcdef01234567");
        assertThat(result.builtAt()).contains(Instant.parse("2026-07-19T10:30:00Z"));
    }

    @Test
    void accepts_missing_optional_metadata_without_inventing_fallbacks() {
        ApplicationBuildInfo result = ApplicationBuildInfoReader.from(propertiesWithVersion());

        assertThat(result.version()).isEqualTo("9.8.7");
        assertThat(result.commit()).isEmpty();
        assertThat(result.builtAt()).isEmpty();
    }

    @Test
    void rejects_missing_required_version() {
        assertThatThrownBy(() -> ApplicationBuildInfoReader.from(new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required embedded build property is missing: build.version");
    }

    @Test
    void rejects_malformed_optional_metadata() {
        var malformedCommit = propertiesWithVersion();
        malformedCommit.setProperty("build.commit", "short");
        var malformedTime = propertiesWithVersion();
        malformedTime.setProperty("build.time", "today");

        assertThatThrownBy(() -> ApplicationBuildInfoReader.from(malformedCommit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("build.commit");
        assertThatThrownBy(() -> ApplicationBuildInfoReader.from(malformedTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("build.time");
    }

    @Test
    void reports_missing_classpath_resource() {
        var emptyClassLoader = new ClassLoader(null) {
        };

        assertThatThrownBy(() -> new ApplicationBuildInfoReader(emptyClassLoader).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ApplicationBuildInfoReader.BUILD_INFO_RESOURCE);
    }

    private Properties propertiesWithVersion() {
        var properties = new Properties();
        properties.setProperty("build.version", " 9.8.7 ");
        return properties;
    }
}
