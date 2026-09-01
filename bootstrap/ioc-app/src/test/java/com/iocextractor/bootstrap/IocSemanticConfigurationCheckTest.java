package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class IocSemanticConfigurationCheckTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsValidOverlayWithoutStartingRuntimeGraph() throws Exception {
        Path candidate = candidate("""
                ioc:
                  runtime:
                    mode: oneshot
                """);
        var output = new StringWriter();
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of("--ioc.runtime.mode=daemon"), writer(output), writer(errors));

        assertThat(result).isZero();
        assertThat(output.toString()).contains("CONFIG.SEMANTIC_VALID");
        assertThat(errors.toString()).isEmpty();
    }

    @Test
    void acceptsTheExactV020ArtifactIdentityOverlay() throws Exception {
        Path candidate = candidate("""
                ioc:
                  artifact-identity:
                    artifacts:
                      - { name: masks, key-columns: [ mask ] }
                      - { name: ip_list, key-columns: [ ip ] }
                      - { name: address_blacklist, key-columns: [ forbidden_url, forbidden_ip ], key-mode: first-non-empty }
                      - { name: hashes, key-columns: [ hash_md5, hash_sha1, hash_sha256 ], key-mode: first-non-empty }
                """);
        var output = new StringWriter();
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(output), writer(errors));

        assertThat(result).isZero();
        assertThat(output.toString()).contains("CONFIG.SEMANTIC_VALID");
        assertThat(errors.toString()).isEmpty();
    }

    @Test
    void rejectsAnUnapprovedIncompleteArtifactIdentityOverlay() throws Exception {
        Path candidate = candidate("""
                ioc:
                  artifact-identity:
                    artifacts:
                      - { name: masks, key-columns: [ mask, source ] }
                """);
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString())
                .contains("CONFIG.SEMANTIC_INVALID", "artifactIdentity.artifacts[0].recordKey")
                .doesNotContain("source ]");
    }

    @Test
    void rejectsUnknownIocPropertyWithCanonicalNameAndNoValue(CapturedOutput processOutput) throws Exception {
        Path candidate = candidate("""
                ioc:
                  runtime:
                    unknown-mode: secret-value-must-not-leak
                """);
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString())
                .contains("CONFIG.SEMANTIC_INVALID", "CONFIG.UNKNOWN_PROPERTY", "ioc.runtime.unknown-mode")
                .doesNotContain("secret-value-must-not-leak");
        assertThat(processOutput).doesNotContain("secret-value-must-not-leak");
    }

    @Test
    void rejectsSemanticInvariant() throws Exception {
        Path candidate = candidate("""
                ioc:
                  ingestion:
                    concurrency: 2
                """);
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString())
                .contains("CONFIG.SEMANTIC_INVALID", "ioc.ingestion.concurrency");
    }

    @Test
    void rejectsCallerControlledConfigLocation() {
        var errors = new StringWriter();

        var result = IocSemanticConfigurationCheck.executeIfRequested(
                new String[] {
                        "--ioc.validate-config=candidate.yml",
                        "--spring.config.location=file:/tmp/other.yml"
                },
                writer(new StringWriter()),
                writer(errors));

        assertThat(result).hasValue(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString()).contains("CONFIG.SEMANTIC_USAGE");
    }

    @Test
    void rejectsCandidateExpansionOfTheConfigurationOnlySourceSet(CapturedOutput processOutput) throws Exception {
        Path candidate = candidate("""
                spring:
                  main:
                    sources: secret-source-class-must-not-leak
                """);
        var errors = new StringWriter();

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        String yamlLogger = "org.springframework.boot.env.OriginTrackedYamlLoader";
        Logger spring = loggerContext.getLogger(yamlLogger);
        Level previousSpringLevel = spring.getLevel();
        int result;
        spring.setLevel(Level.DEBUG);
        try {
            result = IocSemanticConfigurationCheck.validate(
                    candidate, List.of(), writer(new StringWriter()), writer(errors));
        } finally {
            spring.setLevel(previousSpringLevel);
        }

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString())
                .contains("CONFIG.SEMANTIC_SOURCE_OVERRIDE", "spring.main.sources")
                .doesNotContain("secret-source-class-must-not-leak");
        assertThat(processOutput).doesNotContain("secret-source-class-must-not-leak");
    }

    private Path candidate(String content) throws Exception {
        Path candidate = tempDir.resolve("application.candidate.yml");
        Files.writeString(candidate, content);
        return candidate;
    }

    private PrintWriter writer(StringWriter target) {
        return new PrintWriter(target, true);
    }
}
