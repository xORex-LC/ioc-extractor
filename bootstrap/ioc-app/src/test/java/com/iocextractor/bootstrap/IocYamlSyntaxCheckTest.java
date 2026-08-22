package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class IocYamlSyntaxCheckTest {

    @TempDir
    private Path tempDir;

    @Test
    void acceptsValidYamlWithoutStartingSpring() throws Exception {
        Path candidate = tempDir.resolve("candidate.yml");
        Files.writeString(candidate, """
                # operator candidate
                ioc:
                  lifecycle:
                    validity:
                      fixed-ttl: 10m
                """);
        var output = new StringWriter();
        var error = new StringWriter();

        OptionalInt result = IocYamlSyntaxCheck.executeIfRequested(
                new String[]{"--ioc.validate-yaml=" + candidate},
                new PrintWriter(output, true),
                new PrintWriter(error, true));

        assertThat(result).hasValue(0);
        assertThat(output.toString()).contains("CONFIG.YAML_VALID", candidate.toString());
        assertThat(error.toString()).isEmpty();
    }

    @Test
    void reportsStableLocationWithoutEchoingInvalidSourceLine() throws Exception {
        Path candidate = tempDir.resolve("broken.yml");
        String sensitiveLine = "literal-password-that-must-not-be-logged";
        Files.writeString(candidate, sensitiveLine + System.lineSeparator() + "spring:" + System.lineSeparator());
        var output = new StringWriter();
        var error = new StringWriter();

        int result = IocYamlSyntaxCheck.validate(
                candidate,
                new PrintWriter(output, true),
                new PrintWriter(error, true));

        assertThat(result).isEqualTo(IocYamlSyntaxCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString())
                .contains("CONFIG.YAML_INVALID")
                .contains(candidate.toString() + ":2:7")
                .contains("ioc-config apply")
                .doesNotContain(sensitiveLine, "\tat ");
    }

    @Test
    void rejectsMissingFileWithConfigurationExitCode() {
        Path missing = tempDir.resolve("missing.yml");
        var output = new StringWriter();
        var error = new StringWriter();

        int result = IocYamlSyntaxCheck.validate(
                missing,
                new PrintWriter(output, true),
                new PrintWriter(error, true));

        assertThat(result).isEqualTo(IocYamlSyntaxCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString())
                .contains("CONFIG.YAML_UNREADABLE")
                .contains(missing.toString());
    }

    @Test
    void ignoresNormalApplicationArguments() {
        OptionalInt result = IocYamlSyntaxCheck.executeIfRequested(
                new String[]{"extract", "--source", "source.htm"},
                new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()));

        assertThat(result).isEmpty();
    }

    @Test
    void rejectsValidationMixedWithRuntimeArguments() {
        var error = new StringWriter();

        OptionalInt result = IocYamlSyntaxCheck.executeIfRequested(
                new String[]{"--ioc.validate-yaml=candidate.yml", "--ioc.runtime.mode=daemon"},
                new PrintWriter(new StringWriter()),
                new PrintWriter(error, true));

        assertThat(result).hasValue(IocYamlSyntaxCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(error.toString())
                .contains("CONFIG.YAML_USAGE")
                .doesNotContain("Exception", "\tat ");
    }
}
