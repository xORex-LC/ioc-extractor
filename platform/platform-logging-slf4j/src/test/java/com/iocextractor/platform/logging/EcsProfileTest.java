package com.iocextractor.platform.logging;

import com.iocextractor.platform.logging.ecs.EcsFields;
import com.iocextractor.platform.logging.ecs.EventOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.iocextractor.platform.logging.LogCapture.fields;
import static org.assertj.core.api.Assertions.*;

@ResourceLock("slf4j-mdc-adapter")
class EcsProfileTest {
    private final LogCapture capture = new LogCapture();

    @AfterEach
    void releaseAppender() {
        capture.close();
    }

    @Test
    void profileHasExactlyTheFiveQualifiedScalarDescriptors() {
        var declarations = List.of(EcsFields.EVENT_ACTION, EcsFields.EVENT_OUTCOME, EcsFields.EVENT_DURATION,
                EcsFields.ERROR_TYPE, EcsFields.FILE_PATH);
        assertThat(declarations).extracting(LogFieldSpec::key).containsExactly(
                "event.action", "event.outcome", "event.duration", "error.type", "file.path");
        assertThat(declarations).extracting(LogFieldSpec::valueType).containsExactly(
                LogValueType.STRING, LogValueType.STRING, LogValueType.LONG, LogValueType.STRING, LogValueType.STRING);
        assertThat(Arrays.stream(EcsFields.class.getFields()).filter(field -> field.getType() == LogFieldSpec.class))
                .hasSize(5);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,success", "FAILURE,failure", "UNKNOWN,unknown"})
    void outcomeHelperUsesTheExactWireVocabulary(EventOutcome outcome, String expected) {
        LogEvents.info(capture.logger).outcome(outcome).log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyEntriesOf(Map.of("event.outcome", expected));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void durationHelperKeepsNanosecondsWithoutConversion(long nanos) {
        LogEvents.info(capture.logger).durationNanos(nanos).log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyEntriesOf(Map.of("event.duration", nanos));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void negativeDurationIsRejectedBeforeFiltering(long nanos) {
        capture.logger.setLevel(ch.qos.logback.classic.Level.OFF);
        assertThatIllegalArgumentException().isThrownBy(() -> LogEvents.info(capture.logger).durationNanos(nanos));
    }

    @Test
    void actionIsServiceDefinedAndIsNotTrimmed() {
        LogEvents.info(capture.logger).action(" collector_complete ").log();
        assertThat(fields(capture.output.list.getFirst())).containsEntry("event.action", " collector_complete ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankActionIsRejected(String action) {
        assertThatIllegalArgumentException().isThrownBy(() -> LogEvents.info(capture.logger).action(action));
    }

    @Test
    void rejectedHelperUpdatesPreserveThePreviousRecord() {
        var event = LogEvents.info(capture.logger).action("collect_complete")
                .outcome(EventOutcome.SUCCESS).durationNanos(12);
        assertThatNullPointerException().isThrownBy(() -> event.action(null));
        assertThatNullPointerException().isThrownBy(() -> event.outcome(null));
        assertThatIllegalArgumentException().isThrownBy(() -> event.action(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> event.durationNanos(-1));
        event.log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "event.action", "collect_complete", "event.outcome", "success", "event.duration", 12L));
    }

    @Test
    void inapplicableOutcomeAndUnmeasuredDurationCanBeOmitted() {
        LogEvents.debug(capture.logger).action("collect_skipped").log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyEntriesOf(Map.of("event.action", "collect_skipped"));
    }

    @Test
    void genericFieldsValidateRepresentationWithoutClaimingEcsSemantics() {
        LogEvents.info(capture.logger).field(EcsFields.EVENT_OUTCOME, "skipped")
                .field(EcsFields.EVENT_DURATION, -1).log();
        assertThat(fields(capture.output.list.getFirst())).containsExactlyInAnyOrderEntriesOf(
                Map.of("event.outcome", "skipped", "event.duration", -1L));
    }
}
