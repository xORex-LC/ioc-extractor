package qualification.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Characterizes reused algorithms; known defects are named explicitly. */
@Tag("contract")
class GenericLoggingProbeTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger("qualification.generic");

    @AfterEach
    void releaseLoggerAndContext() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
        logger.setLevel(null);
        MDC.clear();
    }

    @ParameterizedTest
    @MethodSource("validScalars")
    void normalizesSupportedScalarValues(ProbeField field, Object input, Object expected) {
        assertThat(LogValueNormalizer.normalize(field, input)).isEqualTo(expected);
    }

    static Stream<Arguments> validScalars() {
        var id = UUID.fromString("00000000-0000-0000-0000-000000000042");
        return Stream.of(
                Arguments.of(ProbeField.TASK, "task-42", "task-42"),
                Arguments.of(ProbeField.TASK, new StringBuilder("task-42"), "task-42"),
                Arguments.of(ProbeField.TASK, Path.of("example.txt"), "example.txt"),
                Arguments.of(ProbeField.TASK, id, id.toString()),
                Arguments.of(ProbeField.TASK, EventOutcome.SUCCESS, "SUCCESS"),
                Arguments.of(ProbeField.ITEMS, (byte) 1, 1L),
                Arguments.of(ProbeField.ITEMS, (short) 2, 2L),
                Arguments.of(ProbeField.ITEMS, 3, 3L),
                Arguments.of(ProbeField.ITEMS, Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(ProbeField.RETRY, true, true),
                Arguments.of(ProbeField.TASK, null, null));
    }

    @ParameterizedTest
    @MethodSource("invalidScalars")
    void rejectsUnsupportedRepresentations(ProbeField field, Object value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogValueNormalizer.normalize(field, value))
                .withMessageContaining(field.key());
    }

    static Stream<Arguments> invalidScalars() {
        return Stream.of(Arguments.of(ProbeField.ITEMS, "7"),
                Arguments.of(ProbeField.ITEMS, 7.0),
                Arguments.of(ProbeField.RETRY, "true"),
                Arguments.of(ProbeField.RETRY, 1),
                Arguments.of(ProbeField.TASK, 42),
                Arguments.of(ProbeField.TASK, new Object()),
                Arguments.of(ProbeField.ITEMS, "[redacted]"),
                Arguments.of(ProbeField.RETRY, "[redacted]"));
    }

    @Test
    void unrelatedCatalogEmitsTypedFieldsWithSameKeyMdcPrecedence() {
        var output = capture();
        MDC.put(ProbeField.ITEMS.key(), "ambient-string");
        try (var scope = MdcScope.open()) {
            scope.put(ProbeField.TASK, "task-42");
            LogEvents.info(logger).action(EventAction.COLLECT_COMPLETE)
                    .outcome(EventOutcome.SUCCESS).field(ProbeField.ITEMS, 4)
                    .field(ProbeField.RETRY, false).log();
        }
        assertThat(output.list).singleElement().satisfies(event -> {
            assertThat(event.getMDCPropertyMap()).containsEntry(ProbeField.TASK.key(), "task-42")
                    .doesNotContainKey(ProbeField.ITEMS.key());
            assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo(ProbeField.ITEMS.key());
                assertThat(pair.value).isEqualTo(4L);
            });
        });
        assertThat(MDC.get(ProbeField.ITEMS.key())).isEqualTo("ambient-string");
        assertThat(MDC.get(ProbeField.TASK.key())).isNull();
    }

    @Test
    void equivalentDescriptorsReplaceOneField() {
        var output = capture();
        LogEvents.info(logger).field(ProbeField.ITEMS, 1)
                .field(new ProbeField("collector.items", LogValueType.LONG), 2).log();
        assertThat(output.list.getFirst().getKeyValuePairs()).singleElement()
                .satisfies(pair -> assertThat(pair.value).isEqualTo(2L));
    }

    @Test
    void baselineDefectRecordIdentityAllowsConflictingWireTypes() {
        var output = capture();
        LogEvents.info(logger).field(ProbeField.ITEMS, 1)
                .field(new ProbeField("collector.items", LogValueType.BOOLEAN), true).log();
        assertThat(output.list.getFirst().getKeyValuePairs())
                .extracting(pair -> pair.key).containsExactly("collector.items", "collector.items");
    }

    @Test
    void baselineDefectNullForgetsPreviouslyDeclaredType() {
        var output = capture();
        LogEvents.info(logger).field(ProbeField.ITEMS, 1).field(ProbeField.ITEMS, null)
                .field(new ProbeField("collector.items", LogValueType.BOOLEAN), true).log();
        assertThat(output.list.getFirst().getKeyValuePairs()).singleElement()
                .satisfies(pair -> assertThat(pair.value).isEqualTo(true));
    }

    @Test
    void disabledLevelStillRejectsWrongType() {
        logger.setLevel(Level.OFF);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogEvents.info(logger).field(ProbeField.ITEMS, "7"));
    }

    @Test
    void nestedScopesRestoreOwnedKeysAndPreserveUnrelatedWritesOnFailure() {
        MDC.put(ProbeField.TASK.key(), "ambient");
        assertThatIllegalStateException().isThrownBy(() -> {
            try (var outer = MdcScope.open()) {
                outer.put(ProbeField.TASK, "outer");
                try (var inner = MdcScope.open()) {
                    inner.put(ProbeField.TASK, "inner").hide(ProbeField.TASK);
                    assertThat(MDC.get(ProbeField.TASK.key())).isNull();
                }
                assertThat(MDC.get(ProbeField.TASK.key())).isEqualTo("outer");
                MDC.put("collector.unowned", "keep");
                throw new IllegalStateException("synthetic operation failure");
            }
        });
        assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(
                Map.of(ProbeField.TASK.key(), "ambient", "collector.unowned", "keep"));
    }

    @Test
    void baselineDefectFluentAcquisitionCanLeaveMdcBehind() {
        assertThatIllegalArgumentException().isThrownBy(() -> {
            try (var scope = MdcScope.open().put(ProbeField.TASK, "leaked")
                    .put(ProbeField.ITEMS, 1)) {
                throw new AssertionError("resource acquisition should fail");
            }
        });
        assertThat(MDC.get(ProbeField.TASK.key())).isEqualTo("leaked");
    }

    @Test
    void acquiringBeforePopulationCleansUpPartialSetup() {
        assertThatIllegalArgumentException().isThrownBy(() -> {
            try (var scope = MdcScope.open()) {
                scope.put(ProbeField.TASK, "temporary").put(ProbeField.ITEMS, 1);
            }
        });
        assertThat(MDC.get(ProbeField.TASK.key())).isNull();
    }

    @Test
    void closeIsIdempotentAndRejectsLaterWrites() {
        var scope = MdcScope.open().put(ProbeField.TASK, "temporary");
        scope.close();
        scope.close();
        assertThatIllegalStateException().isThrownBy(() -> scope.hide(ProbeField.TASK));
    }

    @Test
    void nullOmissionLeavesAmbientValueButExplicitMaskWins() {
        var output = capture();
        MDC.put(ProbeField.TOKEN.key(), "synthetic-ambient");
        LogEvents.info(logger).field(ProbeField.TOKEN, null).log();
        LogEvents.error(logger).field(ProbeField.TOKEN, "[redacted]").log();
        assertThat(output.list.getFirst().getMDCPropertyMap())
                .containsEntry(ProbeField.TOKEN.key(), "synthetic-ambient");
        assertThat(output.list.getLast().getMDCPropertyMap()).doesNotContainKey(ProbeField.TOKEN.key());
        assertThat(output.list.getLast().getKeyValuePairs()).singleElement()
                .satisfies(pair -> assertThat(pair.value).isEqualTo("[redacted]"));
        assertThat(MDC.get(ProbeField.TOKEN.key())).isEqualTo("synthetic-ambient");
    }

    @Test
    @Timeout(5)
    void workerReconstructsItsOwnContextFromExplicitMetadata() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        MDC.put(ProbeField.TASK.key(), "parent");
        try {
            var result = worker.submit(() -> {
                assertThat(MDC.get(ProbeField.TASK.key())).isNull();
                try (var scope = MdcScope.open()) {
                    scope.put(ProbeField.TASK, "task-from-command");
                    assertThat(MDC.get(ProbeField.TASK.key())).isEqualTo("task-from-command");
                }
                return MDC.get(ProbeField.TASK.key());
            });
            assertThat(result.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(MDC.get(ProbeField.TASK.key())).isEqualTo("parent");
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("urlExamples")
    void urlHelperHasTheRecordedLexicalScope(String input, String expected) {
        assertThat(SensitiveLogValueSanitizer.sanitize(input)).isEqualTo(expected);
    }

    static Stream<Arguments> urlExamples() {
        return Stream.of(Arguments.of(null, null), Arguments.of("", ""),
                Arguments.of("plain", "plain"),
                Arguments.of("https://u:p@example.test/p?q=secret#f", "https://<redacted>@example.test/p?<redacted>#f"),
                Arguments.of("u:p@example.test/p", "u:p@example.test/p"),
                Arguments.of("example.test/p?q=secret", "example.test/p?<redacted>"),
                Arguments.of("https://a@b@example.test/p", "https://<redacted>@example.test/p"),
                Arguments.of("https://example.test/secret#secret", "https://example.test/secret#secret"),
                Arguments.of("https://example.test/#f?q=secret", "https://example.test/#f?<redacted>"),
                Arguments.of("https://example.test/?a?b#f#z", "https://example.test/?<redacted>#f#z"),
                Arguments.of("https://example.test/p@visible", "https://example.test/p@visible"),
                Arguments.of("https://u:p@", "https://<redacted>@"));
    }

    private ListAppender<ILoggingEvent> capture() {
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
