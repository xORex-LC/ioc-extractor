package com.iocextractor.bootstrap;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Bootstrap observer translating control-event lifecycle signals into ECS-shaped logs. */
public final class LoggingControlEventObserver implements ControlEventObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingControlEventObserver.class);

    @Override
    public void published(ControlEvent event) {
        debugEvent(EventAction.EVENT_PUBLISH, event, EventOutcome.SUCCESS)
                .message("control event published")
                .log();
    }

    @Override
    public void publishFailed(ControlEvent event, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        errorEvent(EventAction.EVENT_PUBLISH, event, EventOutcome.FAILURE)
                .message("control event publication failed")
                .log(failure);
    }

    @Override
    public void dispatching(ControlEvent event, String handlerName) {
        debugEvent(EventAction.EVENT_DISPATCH, event, EventOutcome.UNKNOWN)
                .field(LogField.IOC_EVENT_HANDLER, requireText(handlerName, "handlerName"))
                .message("control event dispatch started")
                .log();
    }

    @Override
    public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        errorEvent(EventAction.EVENT_DISPATCH, event, EventOutcome.FAILURE)
                .field(LogField.IOC_EVENT_HANDLER, requireText(handlerName, "handlerName"))
                .message("control event dispatch failed")
                .log(failure);
    }

    private LogEvent debugEvent(EventAction action, ControlEvent event, EventOutcome outcome) {
        return event(LogEvents.debug(log), action, event, outcome);
    }

    private LogEvent errorEvent(EventAction action, ControlEvent event, EventOutcome outcome) {
        return event(LogEvents.error(log), action, event, outcome);
    }

    private LogEvent event(LogEvent logEvent, EventAction action, ControlEvent event, EventOutcome outcome) {
        ControlEventMetadata metadata = Objects.requireNonNull(event, "event").metadata();
        LogEvent enriched = logEvent.action(action)
                .outcome(outcome)
                .field(LogField.IOC_EVENT_ID, metadata.eventId())
                .field(LogField.EVENT_TYPE, metadata.eventType())
                .field(LogField.IOC_EVENT_VERSION, metadata.eventVersion())
                .field(LogField.IOC_EVENT_CORRELATION_ID, metadata.correlationId());
        metadata.causation().ifPresent(causation ->
                enriched.field(LogField.IOC_EVENT_CAUSATION_ID, causation));
        return enriched;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
