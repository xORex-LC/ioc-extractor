package com.iocextractor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BuildInfoContractTest {

    private static final String EXPECTED_PROJECT_VERSION_PROPERTY = "test.project.version";

    @Test
    void generated_build_identity_matches_filtered_runtime_version() throws IOException {
        var buildInfo = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("META-INF/build-info.properties"));
        var application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        var expectedProjectVersion = System.getProperty(EXPECTED_PROJECT_VERSION_PROPERTY);
        var configuredServiceVersion = (String) application.getProperty(
                "logging.structured.ecs.service.version");

        assertThat(expectedProjectVersion)
                .as("Surefire must expose the effective Maven project.version")
                .isNotBlank();
        assertThat(configuredServiceVersion)
                .as("resource filtering must replace @project.version@")
                .isEqualTo(expectedProjectVersion)
                .doesNotContain("@project.version@");

        assertThat(buildInfo)
                .containsEntry("build.group", "com.iocextractor")
                .containsEntry("build.artifact", "ioc-app")
                .containsEntry("build.name", "ioc-app")
                .containsEntry("build.version", expectedProjectVersion);
        assertThat(buildInfo.getProperty("build.time")).isNotBlank();
        assertThatCode(() -> Instant.parse(buildInfo.getProperty("build.time")))
                .doesNotThrowAnyException();

        var expectedCommit = System.getProperty("build.commit");
        if (expectedCommit == null) {
            assertThat(buildInfo).doesNotContainKey("build.commit");
        } else {
            assertThat(buildInfo).containsEntry("build.commit", expectedCommit);
        }
    }
}
