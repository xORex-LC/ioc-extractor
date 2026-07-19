package com.iocextractor.observability;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

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
    void boot_ecs_encoder_preserves_json_scalar_types_and_string_correlation() throws IOException {
        var configuredVersion = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst()
                .getProperty("logging.structured.ecs.service.version");
        assertThat(configuredVersion).isInstanceOf(String.class);
        var buildVersion = (String) configuredVersion;
        assertThat(buildVersion).isNotBlank();
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
        var json = new ObjectMapper().readTree(Files.readString(logFile));
        assertThat(json.path("ecs").path("version").asText()).isEqualTo("8.11");
        assertThat(json.path("service").path("name").asText()).isEqualTo("ioc-extractor");
        assertThat(json.path("service").path("version").asText()).isEqualTo(buildVersion);
        assertThat(json.path("event").path("dataset").asText()).isEqualTo("ioc-extractor");
        assertThat(json.path("event").path("action").asText()).isEqualTo(EventAction.APP_START.value());
        assertThat(json.path("event").path("outcome").asText()).isEqualTo(EventOutcome.SUCCESS.value());
        assertThat(json.path("event").path("duration").isIntegralNumber()).isTrue();
        assertThat(json.path("event").path("duration").longValue()).isEqualTo(18_324_056L);
        assertThat(json.path("ioc").path("rows").isIntegralNumber()).isTrue();
        assertThat(json.path("ioc").path("rows").longValue()).isEqualTo(58L);
        assertThat(json.path("ioc").path("sync").path("shed_to_reconcile").isBoolean()).isTrue();
        assertThat(json.path("ioc").path("sync").path("shed_to_reconcile").booleanValue()).isTrue();
        assertThat(json.path("ioc").path("run").path("id").isTextual()).isTrue();
        assertThat(json.path("ioc").path("run").path("id").asText()).isEqualTo("00017");
        assertThat(json.path("ioc").path("mode").asText()).isEqualTo("daemon");
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
        event.setTimeStamp(System.currentTimeMillis());
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
}
