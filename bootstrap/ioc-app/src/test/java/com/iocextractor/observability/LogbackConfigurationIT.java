package com.iocextractor.observability;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.bootstrap.IocEcsStructuredLogEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ContractTest
class LogbackConfigurationIT {

    private static final String EXPECTED_PROJECT_VERSION_PROPERTY = "test.project.version";

    @Test
    void logback_configuration_declares_daemon_ecs_file_appender() throws IOException {
        var config = Files.readString(Path.of("src/main/resources/logback-spring.xml"));

        assertThat(config).contains("<springProfile name=\"daemon\">");
        assertThat(config).contains("com.iocextractor.bootstrap.IocEcsStructuredLogEncoder");
        assertThat(config).contains("<format>ecs</format>");
        assertThat(config).contains("<eventDataset>${SERVICE_NAME}</eventDataset>");
        assertThat(config).doesNotContain("co.elastic.logging");
        assertThat(config).contains("SizeAndTimeBasedRollingPolicy");
        assertThat(config).contains("${LOG_PATH}/ioc-extractor.ecs.json");
    }

    @Test
    void boot_ecs_encoder_matches_exact_payload_and_consumer_queries() throws IOException {
        var configuredVersion = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst()
                .getProperty("logging.structured.ecs.service.version");
        assertThat(configuredVersion).isInstanceOf(String.class);
        var buildVersion = (String) configuredVersion;
        assertThat(buildVersion)
                .isEqualTo(System.getProperty(EXPECTED_PROJECT_VERSION_PROPERTY))
                .doesNotContain("@project.version@");
        var logFile = Path.of("target/test-logs/daemon-programmatic/ioc-extractor.ecs.json");
        Files.createDirectories(logFile.getParent());
        Files.deleteIfExists(logFile);

        var context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment(buildVersion));
        var encoder = ecsEncoder(context);
        byte[] encoded = encoder.encode(loggingEvent(context));
        Files.write(logFile, encoded);
        context.stop();

        assertThat(logFile).exists();
        String normalizedPayload = Files.readString(logFile)
                .replace("\"version\":\"" + buildVersion + "\"", "\"version\":\"<VERSION>\"");
        assertThat(normalizedPayload).isEqualTo(resource("consumer/logging/app-start.json"));

        var json = new ObjectMapper().readTree(normalizedPayload);
        for (String query : resourceLines("consumer/logging/app-start-queries.tsv")) {
            if (query.isBlank() || query.startsWith("#")) {
                continue;
            }
            assertQuery(json, query);
        }
    }

    private StructuredLogEncoder ecsEncoder(LoggerContext context) {
        var encoder = new IocEcsStructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.setEventDataset("ioc-extractor");
        encoder.start();
        return encoder;
    }

    private LoggingEvent loggingEvent(LoggerContext context) {
        var event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("test.ecs-file");
        event.setLevel(Level.INFO);
        event.setMessage("daemon event");
        event.setThreadName("main");
        event.setTimeStamp(Instant.parse("2026-09-06T00:00:00Z").toEpochMilli());
        event.setMDCPropertyMap(Map.of(
                LogField.IOC_RUN_ID.key(), "00017",
                LogField.IOC_MODE.key(), "daemon"));
        event.addKeyValuePair(new KeyValuePair(LogField.EVENT_ACTION.key(), EventAction.APP_START.value()));
        event.addKeyValuePair(new KeyValuePair(LogField.EVENT_OUTCOME.key(), EventOutcome.SUCCESS.value()));
        event.addKeyValuePair(new KeyValuePair(LogField.EVENT_DURATION.key(), 18_324_056L));
        event.addKeyValuePair(new KeyValuePair(LogField.IOC_ROWS.key(), 58L));
        event.addKeyValuePair(new KeyValuePair(LogField.IOC_SYNC_SHED_TO_RECONCILE.key(), true));
        return event;
    }

    private StandardEnvironment environment(String buildVersion) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "logging.structured.ecs.service.name", "ioc-extractor",
                "logging.structured.ecs.service.version", buildVersion)));
        return environment;
    }

    private void assertQuery(com.fasterxml.jackson.databind.JsonNode json, String query) {
        String[] columns = query.split("\t", -1);
        assertThat(columns).as("query fixture row").hasSize(3);
        var value = json.at(columns[0]);
        switch (columns[1]) {
            case "string" -> {
                assertThat(value.isTextual()).as(columns[0] + " scalar type").isTrue();
                assertThat(value.textValue()).as(columns[0]).isEqualTo(columns[2]);
            }
            case "integer" -> {
                assertThat(value.isIntegralNumber()).as(columns[0] + " scalar type").isTrue();
                assertThat(value.longValue()).as(columns[0]).isEqualTo(Long.parseLong(columns[2]));
            }
            case "boolean" -> {
                assertThat(value.isBoolean()).as(columns[0] + " scalar type").isTrue();
                assertThat(value.booleanValue()).as(columns[0]).isEqualTo(Boolean.parseBoolean(columns[2]));
            }
            default -> throw new IllegalArgumentException("Unknown query fixture type: " + columns[1]);
        }
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing consumer fixture: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> resourceLines(String name) throws IOException {
        return resource(name).lines().toList();
    }
}
