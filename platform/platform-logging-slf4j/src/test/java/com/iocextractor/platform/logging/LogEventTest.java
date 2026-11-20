package com.iocextractor.platform.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.iocextractor.platform.logging.LogCapture.*;
import static org.assertj.core.api.Assertions.*;

@ResourceLock("slf4j-mdc-adapter")
class LogEventTest {
    private final LogCapture capture = new LogCapture();

    @AfterEach
    void releaseAppender() {
        capture.close();
    }

    @ParameterizedTest
    @MethodSource("scalarValues")
    void emitsNormalizedScalarsThroughThePublicFacade(LogFieldSpec field, Object input, Object expected) {
        LogEvents.info(capture.logger).field(field, input).log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyEntriesOf(Map.of(field.key(), expected));
    }

    static Stream<Arguments> scalarValues() {
        var identifier = UUID.fromString("00000000-0000-0000-0000-000000000042");
        return Stream.of(Arguments.of(TASK, "task-42", "task-42"),
                Arguments.of(TASK, new StringBuilder("task-42"), "task-42"),
                Arguments.of(TASK, Path.of("relative.txt"), "relative.txt"),
                Arguments.of(TASK, identifier, identifier.toString()),
                Arguments.of(TASK, LogValueType.LONG, "LONG"),
                Arguments.of(ITEMS, (byte) 1, 1L), Arguments.of(ITEMS, (short) 2, 2L),
                Arguments.of(ITEMS, 3, 3L), Arguments.of(ITEMS, Long.MIN_VALUE, Long.MIN_VALUE),
                Arguments.of(ITEMS, Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(RETRY, true, true), Arguments.of(RETRY, false, false));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsWrongRepresentationsWithoutDisclosingTheValue(LogFieldSpec field, Object input) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogEvents.info(capture.logger).field(field, input))
                .withMessageContaining(field.key()).withMessageContaining(field.valueType().name())
                .withMessageNotContaining("synthetic-secret");
        assertThat(capture.output.list).isEmpty();
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(Arguments.of(TASK, 42), Arguments.of(TASK, new Object()),
                Arguments.of(TASK, new String[]{"synthetic-secret"}), Arguments.of(TASK, java.util.List.of("info")),
                Arguments.of(ITEMS, "synthetic-secret"), Arguments.of(ITEMS, 1.0),
                Arguments.of(ITEMS, java.math.BigInteger.ONE), Arguments.of(RETRY, "synthetic-secret"),
                Arguments.of(RETRY, 1), Arguments.of(RETRY, Map.of("value", true)));
    }

    @Test
    void equivalentDescriptorsReplaceOneKeyAndSnapshotMutableText() {
        var text = new StringBuilder("before");
        var event = LogEvents.info(capture.logger).field(TASK, text).field(ITEMS, 1)
                .field(new LogFieldSpec(ITEMS.key(), LogValueType.LONG), 2);
        text.append("-changed");
        event.log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyInAnyOrderEntriesOf(
                Map.of(TASK.key(), "before", ITEMS.key(), 2L));
        assertThat(capture.output.list.getFirst().getKeyValuePairs()).extracting(pair -> pair.key)
                .containsExactly(TASK.key(), ITEMS.key());
    }

    @Test
    void conflictingTypeLeavesThePreviousFieldIntact() {
        var event = LogEvents.info(capture.logger).field(ITEMS, 12);
        assertThatIllegalArgumentException().isThrownBy(() -> event.field(
                new LogFieldSpec(ITEMS.key(), LogValueType.STRING), "synthetic-secret"))
                .withMessageContaining("LONG").withMessageContaining("STRING")
                .withMessageNotContaining("synthetic-secret");
        event.log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyEntriesOf(Map.of(ITEMS.key(), 12L));
    }

    @Test
    void firstNullAndRepeatedNullRetainTheDeclaredType() {
        var event = LogEvents.info(capture.logger).field(ITEMS, null).field(ITEMS, null);
        assertThatIllegalArgumentException().isThrownBy(() -> event.field(
                new LogFieldSpec(ITEMS.key(), LogValueType.BOOLEAN), null));
        event.field(ITEMS, 12).field(ITEMS, null);
        assertThatIllegalArgumentException().isThrownBy(() -> event.field(
                new LogFieldSpec(ITEMS.key(), LogValueType.STRING), "12"));
        event.log();
        assertThat(fields(capture.output.list.getFirst())).isEmpty();
        event.field(ITEMS, 13).log();
        assertThat(fields(capture.output.list.getLast())).containsEntry(ITEMS.key(), 13L);
    }

    @Test
    void rejectedValueDoesNotReplaceOrEstablishADeclaration() {
        var event = LogEvents.info(capture.logger).field(ITEMS, 12);
        assertThatIllegalArgumentException().isThrownBy(() -> event.field(ITEMS, "bad"));
        var newField = new LogFieldSpec("collector.new", LogValueType.LONG);
        assertThatIllegalArgumentException().isThrownBy(() -> event.field(newField, "bad"));
        event.field(new LogFieldSpec("collector.new", LogValueType.STRING), "accepted").log();
        assertThat(fields(capture.output.list.getFirst()))
                .containsEntry(ITEMS.key(), 12L).containsEntry("collector.new", "accepted");
    }

    @Test
    void emissionShadowsExactMdcKeysAndRestoresThemAfterwards() {
        MDC.put(ITEMS.key(), "ambient-number");
        MDC.put(RETRY.key(), "ambient-boolean");
        MDC.put(TASK.key(), "ambient-task");
        LogEvents.info(capture.logger).field(ITEMS, 4).field(RETRY, false).log();
        var record = capture.output.list.getFirst();
        assertThat(record.getMDCPropertyMap()).containsEntry(TASK.key(), "ambient-task")
                .doesNotContainKeys(ITEMS.key(), RETRY.key());
        assertThat(MDC.getCopyOfContextMap()).containsEntry(ITEMS.key(), "ambient-number")
                .containsEntry(RETRY.key(), "ambient-boolean");
    }

    @Test
    void nullOmissionLeavesSameKeyAmbientContextVisible() {
        MDC.put(TOKEN.key(), "synthetic-ambient");
        LogEvents.info(capture.logger).field(TOKEN, "removed").field(TOKEN, null).log();
        assertThat(fields(capture.output.list.getFirst())).isEmpty();
        assertThat(capture.output.list.getFirst().getMDCPropertyMap())
                .containsEntry(TOKEN.key(), "synthetic-ambient");
    }

    @ParameterizedTest
    @MethodSource("levels")
    void factoriesPreserveLevelAndDefaultMessage(Level expected, Function<org.slf4j.Logger, LogEvent> factory) {
        var event = factory.apply(capture.logger);
        event.log(null);
        event.log();
        assertThat(capture.output.list).hasSize(2).allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(expected);
            assertThat(record.getMessage()).isEmpty();
            assertThat(record.getThrowableProxy()).isNull();
        });
        assertThatNullPointerException().isThrownBy(() -> factory.apply(null));
    }

    static Stream<Arguments> levels() {
        return Stream.of(Arguments.of(Level.TRACE, (Function<org.slf4j.Logger, LogEvent>) LogEvents::trace),
                Arguments.of(Level.DEBUG, (Function<org.slf4j.Logger, LogEvent>) LogEvents::debug),
                Arguments.of(Level.INFO, (Function<org.slf4j.Logger, LogEvent>) LogEvents::info),
                Arguments.of(Level.WARN, (Function<org.slf4j.Logger, LogEvent>) LogEvents::warn),
                Arguments.of(Level.ERROR, (Function<org.slf4j.Logger, LogEvent>) LogEvents::error));
    }

    @Test
    void disabledLevelStillValidatesButDoesNotEmit() {
        capture.logger.setLevel(Level.OFF);
        assertThatIllegalArgumentException().isThrownBy(() -> LogEvents.info(capture.logger).field(ITEMS, "12"));
        assertThatNullPointerException().isThrownBy(() -> LogEvents.info(capture.logger).field(null, "x"));
        assertThatNullPointerException().isThrownBy(() -> LogEvents.info(capture.logger).message(null));
        MDC.put(TASK.key(), "ambient");
        LogEvents.info(capture.logger).field(TASK, "event-local").log();
        assertThat(MDC.get(TASK.key())).isEqualTo("ambient");
        assertThat(capture.output.list).isEmpty();
    }

    @Test
    void failedCollisionPreparationRestoresKeysAlreadyHidden() throws Exception {
        var failure = new IllegalStateException("provider remove failed");
        var adapter = new LogbackMDCAdapter() {
            private boolean failed;
            @Override public void remove(String key) {
                super.remove(key);
                if (ITEMS.key().equals(key) && !failed) {
                    failed = true;
                    throw failure;
                }
            }
        };
        try (var provider = new MdcAdapterSwap(adapter)) {
            MDC.put(TASK.key(), "ambient-task");
            MDC.put(ITEMS.key(), "ambient-number");
            assertThatThrownBy(() -> LogEvents.info(capture.logger)
                    .field(TASK, "task").field(ITEMS, 4).log()).isSameAs(failure);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                    Map.of(TASK.key(), "ambient-task", ITEMS.key(), "ambient-number"));
            assertThat(capture.output.list).isEmpty();
        }
    }

    @Test
    void followsCopyReturningBuildersAndRestoresContextWhenDeliveryFails() {
        var probe = new BuilderProbe();
        var cause = new IllegalStateException("synthetic cause");
        MDC.put(TASK.key(), "ambient");
        LogEvents.info(probe.logger()).field(TASK, "event-local").field(ITEMS, 7)
                .message("completed").log(cause);
        assertThat(probe.fields).containsExactlyInAnyOrderEntriesOf(Map.of(TASK.key(), "event-local", ITEMS.key(), 7L));
        assertThat(probe.cause).isSameAs(cause);
        assertThat(probe.message).isEqualTo("completed");
        assertThat(MDC.get(TASK.key())).isEqualTo("ambient");

        probe.failure = new IllegalStateException("provider delivery failed");
        assertThatThrownBy(() -> LogEvents.info(probe.logger()).field(TASK, "event-local").log())
                .isSameAs(probe.failure);
        assertThat(MDC.get(TASK.key())).isEqualTo("ambient");
    }

    @Test
    @Timeout(6)
    void asyncProviderKeepsTheProducerSnapshotAndTerminates() throws Exception {
        var arrived = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var downstream = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                super.append(event);
                worker.set(Thread.currentThread());
                arrived.countDown();
            }
        };
        var async = new AsyncAppender();
        async.setContext(capture.logger.getLoggerContext());
        async.setMaxFlushTime(1000);
        downstream.setContext(capture.logger.getLoggerContext());
        downstream.start();
        async.addAppender(downstream);
        capture.logger.addAppender(async);
        try {
            async.start();
            MDC.put(TASK.key(), "before-handoff");
            MDC.put(ITEMS.key(), "ambient-number");
            LogEvents.info(capture.logger).field(ITEMS, 11).log();
            MDC.put(TASK.key(), "after-handoff");
            assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(downstream.list).singleElement().satisfies(record -> {
                assertThat(record.getMDCPropertyMap()).containsEntry(TASK.key(), "before-handoff")
                        .doesNotContainKey(ITEMS.key());
                assertThat(fields(record)).containsEntry(ITEMS.key(), 11L);
            });
        } finally {
            capture.logger.detachAppender(async);
            async.stop();
            downstream.stop();
            var thread = worker.get();
            if (thread != null) {
                thread.join(2000);
                assertThat(thread.isAlive()).isFalse();
            }
        }
    }

    private static final class BuilderProbe {
        private Map<String, Object> fields;
        private Throwable cause;
        private String message;
        private RuntimeException failure;

        private org.slf4j.Logger logger() {
            return (org.slf4j.Logger) Proxy.newProxyInstance(org.slf4j.Logger.class.getClassLoader(),
                    new Class<?>[]{org.slf4j.Logger.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "isEnabledForLevel" -> true;
                        case "atLevel" -> builder(Map.of(), null);
                        default -> throw new AssertionError("Unexpected logger call: " + method);
                    });
        }

        private LoggingEventBuilder builder(Map<String, Object> currentFields, Throwable currentCause) {
            return (LoggingEventBuilder) Proxy.newProxyInstance(LoggingEventBuilder.class.getClassLoader(),
                    new Class<?>[]{LoggingEventBuilder.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "addKeyValue" -> {
                            var nextFields = new LinkedHashMap<>(currentFields);
                            nextFields.put((String) arguments[0], arguments[1]);
                            yield builder(Map.copyOf(nextFields), currentCause);
                        }
                        case "setCause" -> builder(currentFields, (Throwable) arguments[0]);
                        case "log" -> {
                            if (failure != null) {
                                throw failure;
                            }
                            fields = currentFields;
                            cause = currentCause;
                            message = (String) arguments[0];
                            yield null;
                        }
                        default -> throw new AssertionError("Unexpected builder call: " + method);
                    });
        }
    }
}
