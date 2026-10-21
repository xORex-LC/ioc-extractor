package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.Errors;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
@Timeout(60)
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

        assertThat(result).withFailMessage(errors.toString()).isZero();
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

        assertThat(result).withFailMessage(errors.toString()).isZero();
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
    void omitsRejectedSelectorAndKeepsBindingLocation(CapturedOutput processOutput) throws Exception {
        Path candidate = candidate("ioc.runtime.mode: synthetic_secret_7d96\n");
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString())
                .contains("CONFIG.SEMANTIC_INVALID", "ioc.runtime.mode")
                .doesNotContain("synthetic_secret_7d96", "Invalid ioc.runtime.mode value");
        assertThat(processOutput).doesNotContain("synthetic_secret_7d96");
    }

    @Test
    void reportsAllSemanticPathsWithoutTreatingBindingFailureAsSuccess() throws Exception {
        Path candidate = candidate("""
                ioc:
                  ingestion:
                    concurrency: 2
                  sync:
                    retry:
                      max-attempts: 0
                """);
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString()).contains("CONFIG.VALIDATION_FAILED",
                "ioc.ingestion.concurrency", "ioc.sync.retry.maxAttempts", "registry checks were not completed");
    }

    @Test
    void retainsRegistryClassificationWithoutPrintingTheRejectedReference() throws Exception {
        Path candidate = candidate("ioc.processing.document-plan: synthetic_secret_7d96\n");
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString()).contains("CONFIG.REGISTRY", "named plans")
                .doesNotContain("synthetic_secret_7d96");
    }

    @Test
    void retainsRemovedKeyMigrationHintWithoutItsValue() throws Exception {
        Path candidate = candidate("ioc.lookup.deduplicate: synthetic_secret_7d96\n");
        var errors = new StringWriter();

        int result = IocSemanticConfigurationCheck.validate(
                candidate, List.of(), writer(new StringWriter()), writer(errors));

        assertThat(result).isEqualTo(IocSemanticConfigurationCheck.CONFIGURATION_ERROR_EXIT_CODE);
        assertThat(errors.toString()).contains("CONFIG.LEGACY_LOOKUP", "ioc.pipeline.deduplicate")
                .doesNotContain("synthetic_secret_7d96");
    }

    @Test
    void excludesValidatorMessagesArgumentsCodesAndRejectedValues() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ioc.sync.endpoints[0].smb.username", "rejected_username_secret",
                "ioc.sync.endpoints[0].smb.password", "rejected_password_secret"));
        assertThat(new Binder(source).bind("ioc.sync.endpoints[0].smb", Bindable.of(Credentials.class)).isBound())
                .as("the fixture must bind before its validator runs").isTrue();
        Validator validator = new Validator() {
            @Override
            public boolean supports(Class<?> type) {
                return type == Credentials.class;
            }

            @Override
            public void validate(Object target, Errors errors) {
                // Supply the same structured errors a custom validator can produce.
                BindingResult result = (BindingResult) errors;
                result.addError(new FieldError(errors.getObjectName(), "username", "rejected_username_secret",
                        false, new String[] { "untrusted_code_secret" },
                        new Object[] { "argument_secret" }, "username_message_secret"));
                result.addError(new FieldError(errors.getObjectName(), "password", "rejected_password_secret",
                        false, new String[] { "untrusted_code_secret" }, null, "password_message_secret"));
                result.addError(new ObjectError(errors.getObjectName(), "global_message_secret"));
            }
        };
        RuntimeException failure = catchThrowableOfType(RuntimeException.class, () -> new Binder(source).bind(
                "ioc.sync.endpoints[0].smb", Bindable.of(Credentials.class), new ValidationBindHandler(validator)));
        assertThat(failure).hasStackTraceContaining("BindValidationException");
        var errors = new StringWriter();

        IocSemanticConfigurationCheck.reportFailure(tempDir.resolve("candidate.yml"), failure, writer(errors));

        assertThat(errors.toString()).contains("CONFIG.VALIDATION_FAILED", "ioc.sync.endpoints[0].smb.username",
                "ioc.sync.endpoints[0].smb.password").doesNotContain("secret");
        assertThat(IocConfigurationCheckFailure.from(failure).toString()).doesNotContain("secret");
        assertThat(IocConfigurationCheckFailure.from(failure).paths()).containsExactly(
                "ioc.sync.endpoints[0].smb.username", "ioc.sync.endpoints[0].smb.password",
                "ioc.sync.endpoints[0].smb");
    }

    @Test
    void truncatesBindingPathBeforeDynamicMapKeyAndOmitsConversionMessage() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ioc.patterns.synthetic_secret_key", "synthetic_secret_value"));
        RuntimeException failure = catchThrowableOfType(RuntimeException.class, () -> new Binder(source).bind(
                "ioc.patterns", Bindable.mapOf(String.class, Integer.class)));
        var errors = new StringWriter();

        IocSemanticConfigurationCheck.reportFailure(tempDir.resolve("candidate.yml"), failure, writer(errors));

        assertThat(errors.toString()).contains("CONFIG.BINDING_FAILED", "ioc.patterns",
                "Semantic and registry checks were not completed").doesNotContain("synthetic_secret");
        assertThat(IocConfigurationCheckFailure.from(failure).toString()).doesNotContain("synthetic_secret");
    }

    @Test
    void classifiesUnknownFailuresWithoutExportingTheirMessageOrCause() {
        RuntimeException failure = new IllegalStateException("outer_secret",
                new IllegalArgumentException("nested_secret"));
        var errors = new StringWriter();

        IocSemanticConfigurationCheck.reportFailure(tempDir.resolve("candidate.yml"), failure, writer(errors));

        assertThat(errors.toString()).contains("CONFIG.CHECK_FAILED", "No successful validation is implied")
                .doesNotContain("secret", "IllegalStateException", "IllegalArgumentException");
        assertThat(IocConfigurationCheckFailure.from(failure).toString()).doesNotContain("secret");
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

    public record Credentials(String username, String password) {
    }
}
