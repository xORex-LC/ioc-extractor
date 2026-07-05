package com.iocextractor.bootstrap;

import com.iocextractor.application.ingest.CanonicalArtifactsChanged;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import com.iocextractor.platform.events.ControlEventObserver;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/** Spring listener that turns canonical-ingest change facts into export scheduler nudges. */
public final class CanonicalArtifactsChangedExportListener {

    private static final String HANDLER = "CanonicalArtifactsChangedExportListener";

    private final ExportNudgeTrigger trigger;
    private final ControlEventObserver observer;

    public CanonicalArtifactsChangedExportListener(ExportNudgeTrigger trigger, ControlEventObserver observer) {
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @EventListener
    public void onCanonicalArtifactsChanged(CanonicalArtifactsChanged event) {
        Objects.requireNonNull(event, "event");
        try (var ignored = mdc(event)) {
            observer.dispatching(event, HANDLER);
            trigger.nudge();
        }
    }

    private MdcScope mdc(CanonicalArtifactsChanged event) {
        return MdcScope.open()
                .put(LogField.IOC_RUN_ID, event.runId())
                .put(LogField.IOC_EVENT_ID, event.metadata().eventId())
                .put(LogField.IOC_EVENT_TYPE, event.metadata().eventType())
                .put(LogField.IOC_EVENT_VERSION, event.metadata().eventVersion())
                .put(LogField.IOC_EVENT_CORRELATION_ID, event.metadata().correlationId())
                .put(LogField.IOC_EVENT_CAUSATION_ID, event.metadata().causationId())
                .put(LogField.IOC_EVENT_HANDLER, HANDLER);
    }
}
