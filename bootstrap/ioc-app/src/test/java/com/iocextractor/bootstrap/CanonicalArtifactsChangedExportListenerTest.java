package com.iocextractor.bootstrap;

import com.iocextractor.application.ingest.CanonicalArtifactsChanged;
import com.iocextractor.observability.LogField;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventObserver;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalArtifactsChangedExportListenerTest {

    @Test
    void dispatchNudgesExportSchedulerWithEventMdc() {
        RecordingTrigger trigger = new RecordingTrigger();
        RecordingObserver observer = new RecordingObserver();
        CanonicalArtifactsChangedExportListener listener =
                new CanonicalArtifactsChangedExportListener(trigger, observer);
        CanonicalArtifactsChanged event = CanonicalArtifactsChanged.from(
                "run-1", List.of("masks", "hashes"), Instant.parse("2026-07-05T00:00:00Z"));

        listener.onCanonicalArtifactsChanged(event);

        assertThat(trigger.nudges).isEqualTo(1);
        assertThat(observer.dispatching).containsExactly(event);
        assertThat(observer.failures).isEmpty();
        assertThat(trigger.mdcSnapshots).singleElement()
                .satisfies(mdc -> assertThat(mdc)
                        .containsEntry(LogField.IOC_RUN_ID.key(), "run-1")
                        .containsEntry(LogField.IOC_EVENT_ID.key(), "canonical-artifacts-changed:run-1")
                        .containsEntry(LogField.IOC_EVENT_TYPE.key(), CanonicalArtifactsChanged.EVENT_TYPE)
                        .containsEntry(LogField.IOC_EVENT_CORRELATION_ID.key(), "run-1")
                        .containsEntry(LogField.IOC_EVENT_HANDLER.key(),
                                "CanonicalArtifactsChangedExportListener"));
        assertThat(MDC.get(LogField.IOC_EVENT_ID.key())).isNull();
    }

    private static final class RecordingTrigger implements ExportNudgeTrigger {
        private final List<Map<String, String>> mdcSnapshots = new ArrayList<>();
        private int nudges;

        @Override
        public void nudge() {
            nudges++;
            mdcSnapshots.add(new LinkedHashMap<>(MDC.getCopyOfContextMap()));
        }
    }

    private static final class RecordingObserver implements ControlEventObserver {
        private final List<ControlEvent> dispatching = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();

        @Override
        public void published(ControlEvent event) {
        }

        @Override
        public void publishFailed(ControlEvent event, RuntimeException failure) {
        }

        @Override
        public void dispatching(ControlEvent event, String handlerName) {
            dispatching.add(event);
        }

        @Override
        public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
            failures.add(failure);
        }
    }
}
