package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class IocYamlConfigurationFailureAnalyzerTest {

    @Test
    void debugFailureReporterCannotRecoverTheOriginalParserProblem() {
        Throwable failure = catchThrowable(() -> new YamlPropertySourceLoader().load(
                "broken", new ByteArrayResource("password: *synthetic_secret_7d96\n"
                        .getBytes(StandardCharsets.UTF_8))));
        FailureAnalysis analysis = new IocYamlConfigurationFailureAnalyzer().analyze(findYamlFailure(failure));
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingFailureAnalysisReporter.class);
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            new LoggingFailureAnalysisReporter().report(analysis);

            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic_secret_7d96");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(analysis.getDescription()).contains("CONFIG.YAML_INVALID", "application YAML:1:11")
                    .doesNotContain("synthetic_secret_7d96");
            assertThat(analysis.getCause()).isNull();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void convertsConfigDataYamlFailureIntoValueFreeOperatorDiagnostic() {
        String sensitiveLine = "literal-password-that-must-not-be-logged";
        Throwable loadingFailure = catchThrowable(() -> new YamlPropertySourceLoader().load(
                "broken",
                new ByteArrayResource((sensitiveLine + System.lineSeparator() + "spring:")
                        .getBytes(StandardCharsets.UTF_8))));
        MarkedYAMLException yamlFailure = findYamlFailure(loadingFailure);

        FailureAnalysis analysis = new IocYamlConfigurationFailureAnalyzer().analyze(yamlFailure);

        assertThat(analysis.getDescription())
                .contains("CONFIG.YAML_INVALID")
                .contains("Location: application YAML:2:7")
                .doesNotContain(sensitiveLine);
        assertThat(analysis.getAction())
                .contains("ioc-config apply")
                .doesNotContain("systemctl restart");
    }

    private static MarkedYAMLException findYamlFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MarkedYAMLException yamlFailure) {
                return yamlFailure;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected a MarkedYAMLException", failure);
    }
}
