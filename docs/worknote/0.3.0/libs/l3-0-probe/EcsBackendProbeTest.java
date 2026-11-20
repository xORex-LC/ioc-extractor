package qualification.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.LoggingEventVO;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.iocextractor.bootstrap.IocEcsStructuredLogEncoder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Exercises the real Boot formatter; baseline defects must remain visible. */
@Tag("integration")
@Tag("contract")
class EcsBackendProbeTest {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @Test
    void encodesIndependentTypedCatalogAndStaticMetadata() throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.action", "collect_complete");
            pair(event, "event.outcome", "success");
            pair(event, "event.duration", 123L);
            pair(event, "collector.items", 4L);
            pair(event, "collector.retry", false);
            var json = fixture.json(event);
            assertThat(json.at("/event/dataset").textValue()).isEqualTo("probe-service");
            assertThat(json.at("/collector/items").isIntegralNumber()).isTrue();
            assertThat(json.at("/collector/retry").isBoolean()).isTrue();
            assertThat(json.at("/ecs/version").textValue()).isEqualTo("8.11");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"export.slice.completed", "skipped"})
    void baselineDoesNotValidateEcsTypeMeaning(String value) throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.type", value);
            assertThat(fixture.json(event).at("/event/type").textValue()).isEqualTo(value);
        }
    }

    @Test
    void correctedControlEventUsesCustomNamespace() throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.action", "event_publish");
            pair(event, "ioc.event.type", "export.slice.completed");
            var json = fixture.json(event);
            assertThat(json.at("/event/type").isMissingNode()).isTrue();
            assertThat(json.at("/ioc/event/type").textValue()).isEqualTo("export.slice.completed");
        }
    }

    @Test
    void correctedInformationalSkipHasDispositionWithoutUnknownOutcome() throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.action", "schema_migrate");
            pair(event, "ioc.operation.disposition", "skipped");
            var json = fixture.json(event);
            assertThat(json.at("/ioc/operation/disposition").textValue()).isEqualTo("skipped");
            assertThat(json.at("/event/type").isMissingNode()).isTrue();
            assertThat(json.at("/event/outcome").isMissingNode()).isTrue();
        }
    }

    @Test
    void backendSupportsArraysButScalarFacadeDoesNot() throws Exception {
        var field = new ProbeField("event.type", LogValueType.STRING);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogValueNormalizer.normalize(field, List.of("info")));
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.type", List.of("info"));
            assertThat(fixture.json(event).at("/event/type").isArray()).isTrue();
        }
    }

    @Test
    void baselineFacadeAcceptsInvalidOutcomeAndNegativeDuration() {
        var event = LogEvents.info(org.slf4j.LoggerFactory.getLogger("qualification.invalid"));
        assertThat(event.field(ProbeField.EVENT_OUTCOME, "skipped").durationNanos(-1)).isSameAs(event);
    }

    @Test
    void baselineDatasetInAmbientContextConflictsWithInjectedDataset() {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            event.setMDCPropertyMap(Map.of("event.dataset", "ambient"));
            assertThatIllegalStateException().isThrownBy(() -> fixture.json(event))
                    .withMessageContaining("event.dataset");
        }
    }

    @Test
    void baselineDatasetOverrideWinsAndEncoderRestoresOriginalPairs() throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.dataset", "override");
            assertThat(fixture.json(event).at("/event/dataset").textValue()).isEqualTo("override");
            assertThat(event.getKeyValuePairs()).hasSize(1);
            var ordinary = fixture.event();
            pair(ordinary, "event.action", "collect_complete");
            var original = ordinary.getKeyValuePairs();
            fixture.json(ordinary);
            assertThat(ordinary.getKeyValuePairs()).isSameAs(original).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"service.name", "log.level", "ecs.version", "process.id"})
    void baselineContextCannotRedefineEncoderOwnedObjects(String field) {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, field, "override");
            assertThatIllegalStateException().isThrownBy(() -> fixture.json(event))
                    .withMessageContaining("already been written");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorTypeOnlyAndCauseOnlyEachProduceOneErrorObject(boolean withCause) throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            if (withCause) {
                event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("synthetic")));
            } else {
                pair(event, "error.type", IllegalStateException.class.getName());
            }
            var json = fixture.json(event);
            assertThat(json.at("/error/type").textValue()).isEqualTo(IllegalStateException.class.getName());
            assertThat(json.at("/error/stack_trace").isMissingNode()).isEqualTo(!withCause);
        }
    }

    @Test
    void baselineErrorCollisionDropsRecordInsideActualAppender() {
        try (var fixture = new EncoderFixture()) {
            var bytes = new ByteArrayOutputStream();
            var appender = new OutputStreamAppender<ILoggingEvent>();
            appender.setName("probe-output");
            appender.setContext(fixture.context);
            appender.setEncoder(fixture.encoder);
            appender.setOutputStream(bytes);
            appender.start();
            var logger = fixture.context.getLogger("qualification.error");
            logger.setAdditive(false);
            logger.setLevel(Level.TRACE);
            logger.addAppender(appender);
            try {
                logger.atError().addKeyValue("error.type", IllegalStateException.class.getName())
                        .setCause(new IllegalStateException("synthetic")).log("synthetic failure");
                assertThat(bytes.size()).isZero();
                assertThat(fixture.context.getStatusManager().getCopyOfStatusList())
                        .anySatisfy(status -> assertThat(status.getThrowable())
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("The name 'error'"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void baselineNestedParentChildCollisionFailsInEitherDirection(boolean parentFirst) {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            event.setMDCPropertyMap(parentFirst ? Map.of("collector", "scalar")
                    : Map.of("collector.task.id", "child"));
            pair(event, parentFirst ? "collector.task.id" : "collector", "value");
            assertThatIllegalStateException().isThrownBy(() -> fixture.json(event))
                    .withMessageContaining("Duplicate nested pairs");
        }
    }

    @Test
    void baselineImmutableEventDoesNotReceiveDataset() throws Exception {
        try (var fixture = new EncoderFixture()) {
            var event = fixture.event();
            pair(event, "event.action", "collect_complete");
            assertThat(fixture.json(LoggingEventVO.build(event)).at("/event/dataset").isMissingNode()).isTrue();
        }
    }

    @Test
    void plainStaticDatasetSettingDoesNotMergeWithDynamicEventFields() {
        try (var fixture = new EncoderFixture()) {
            fixture.environment.getPropertySources().addFirst(new MapPropertySource("static-json",
                    Map.of("logging.structured.json.add.event.dataset", "probe-service")));
            var standard = new StructuredLogEncoder();
            standard.setContext(fixture.context);
            standard.setFormat("ecs");
            try {
                standard.start();
                var event = fixture.event();
                pair(event, "event.action", "collect_complete");
                assertThatIllegalStateException().isThrownBy(() -> standard.encode(event))
                        .withMessageContaining("event");
            } finally {
                standard.stop();
            }
        }
    }

    private static void pair(LoggingEvent event, String key, Object value) {
        event.addKeyValuePair(new KeyValuePair(key, value));
    }

    private static final class EncoderFixture implements AutoCloseable {
        private final LoggerContext context = new LoggerContext();
        private final StandardEnvironment environment = new StandardEnvironment();
        private final IocEcsStructuredLogEncoder encoder = new IocEcsStructuredLogEncoder();

        private EncoderFixture() {
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource("synthetic", Map.of(
                    "logging.structured.ecs.service.name", "probe-service")));
            context.setMDCAdapter(new LogbackMDCAdapter());
            context.putObject(Environment.class.getName(), environment);
            encoder.setContext(context);
            encoder.setFormat("ecs");
            encoder.setEventDataset("probe-service");
            encoder.start();
        }

        private LoggingEvent event() {
            var event = new LoggingEvent();
            event.setLoggerContext(context);
            event.setLoggerName("qualification.ecs");
            event.setLevel(Level.INFO);
            event.setMessage("synthetic");
            event.setThreadName("main");
            event.setTimeStamp(0);
            return event;
        }

        private JsonNode json(ILoggingEvent event) throws Exception {
            return JSON.readTree(encoder.encode(event));
        }

        @Override
        public void close() {
            encoder.stop();
            context.stop();
        }
    }
}
