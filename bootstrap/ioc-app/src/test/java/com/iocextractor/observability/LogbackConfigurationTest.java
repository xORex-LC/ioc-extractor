package com.iocextractor.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import co.elastic.logging.logback.EcsEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

    @Test
    void logback_configuration_declares_daemon_ecs_file_appender() throws IOException {
        var config = Files.readString(Path.of("src/main/resources/logback-spring.xml"));

        assertThat(config).contains("<springProfile name=\"daemon\">");
        assertThat(config).contains("co.elastic.logging.logback.EcsEncoder");
        assertThat(config).contains("<eventDataset>ioc-extractor</eventDataset>");
        assertThat(config).contains("SizeAndTimeBasedRollingPolicy");
        assertThat(config).contains("${LOG_PATH}/ioc-extractor.ecs.json");
    }

    @Test
    void ecs_encoder_writes_json_file_with_mdc_fields() throws IOException {
        var logFile = Path.of("target/test-logs/daemon-programmatic/ioc-extractor.ecs.json");
        Files.createDirectories(logFile.getParent());
        Files.deleteIfExists(logFile);

        var context = new LoggerContext();
        var encoder = ecsEncoder(context);
        byte[] encoded;
        try (var ignored = MdcScope.open()
                .put(LogField.EVENT_ACTION, EventAction.APP_START.value())
                .put(LogField.EVENT_OUTCOME, EventOutcome.SUCCESS.value())
                .put(LogField.IOC_MODE, ObservabilityMode.DAEMON.value())) {
            encoded = encoder.encode(loggingEvent(context));
        }
        Files.write(logFile, encoded);
        context.stop();

        assertThat(logFile).exists();
        var content = Files.readString(logFile);
        assertThat(content).contains("\"ecs.version\"");
        assertThat(content).contains("\"service.name\":\"ioc-extractor\"");
        assertThat(content).contains("\"event.dataset\":\"ioc-extractor\"");
        assertThat(content).contains("\"event.action\":\"app_start\"");
        assertThat(content).contains("\"ioc.mode\":\"daemon\"");
    }

    private EcsEncoder ecsEncoder(LoggerContext context) {
        var encoder = new EcsEncoder();
        encoder.setContext(context);
        encoder.setServiceName("ioc-extractor");
        encoder.setServiceVersion("0.1.0-SNAPSHOT");
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
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());
        return event;
    }
}
