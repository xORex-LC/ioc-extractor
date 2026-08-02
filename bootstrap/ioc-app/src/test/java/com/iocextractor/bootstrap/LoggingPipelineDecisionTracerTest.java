package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPipelineDecisionTracerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingPipelineDecisionTracer.class);

    @AfterEach
    void restoreLogger() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
        logger.setLevel(null);
    }

    @Test
    void configurationGatePreventsRendererInvocation() {
        logger.setLevel(Level.TRACE);
        var renders = new AtomicInteger();
        var tracer = new LoggingPipelineDecisionTracer(logger, false, ignored -> renders.incrementAndGet());

        tracer.trace(decision("example.com"));

        assertThat(tracer.isEnabled()).isFalse();
        assertThat(renders).hasValue(0);
    }

    @Test
    void loggerGatePreventsRendererInvocation() {
        logger.setLevel(Level.INFO);
        var renders = new AtomicInteger();
        var tracer = new LoggingPipelineDecisionTracer(logger, true, ignored -> renders.incrementAndGet());

        tracer.trace(decision("example.com"));

        assertThat(tracer.isEnabled()).isFalse();
        assertThat(renders).hasValue(0);
    }

    @Test
    void emitsStructuredTraceAndRedactsUrlQuery() {
        var appender = appender();
        var tracer = new LoggingPipelineDecisionTracer(logger, true);

        tracer.trace(decision("https://user:password@example.com/path?token=secret#fragment"));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.TRACE);
            assertThat(eventFields(event))
                    .containsEntry(LogField.EVENT_ACTION.key(), EventAction.PIPELINE_ITEM_DECISION.value())
                    .containsEntry(LogField.IOC_DECISION_KIND.key(), "extraction")
                    .containsEntry(LogField.IOC_DECISION_OUTCOME.key(), "accepted")
                    .containsEntry(LogField.IOC_INDICATOR_TYPE.key(), "URL")
                    .containsEntry(LogField.IOC_DECISION_PATTERN.key(), "url-pattern")
                    .containsEntry(LogField.IOC_SPAN_START.key(), 8L)
                    .containsEntry(LogField.IOC_SPAN_END.key(), 61L)
                    .containsEntry(LogField.IOC_ITEM_VALUE.key(),
                            "https://<redacted>@example.com/path?<redacted>#fragment");
            assertThat((String) eventFields(event).get(LogField.IOC_ITEM_IDENTITY.key()))
                    .startsWith("url:")
                    .endsWith("@8:61")
                    .doesNotContain("password", "secret");
        });
    }

    @Test
    void rendererFailureDoesNotChangeProcessingOutcome() {
        logger.setLevel(Level.TRACE);
        var tracer = new LoggingPipelineDecisionTracer(logger, true, ignored -> {
            throw new IllegalStateException("broken renderer");
        });

        org.assertj.core.api.Assertions.assertThatCode(() -> tracer.trace(decision("example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void derivesMachineIdentityIndependentlyOfDefaultLocale() {
        var appender = appender();
        var tracer = new LoggingPipelineDecisionTracer(logger, true);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            tracer.trace(PipelineItemDecision.builder(PipelineDecisionKind.EXTRACTION, "accepted")
                    .item("IPV4", "192.0.2.1")
                    .build());
        } finally {
            Locale.setDefault(previous);
        }

        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat((String) eventFields(event).get(LogField.IOC_ITEM_IDENTITY.key()))
                        .startsWith("ipv4:"));
    }

    private PipelineItemDecision decision(String value) {
        return PipelineItemDecision.builder(PipelineDecisionKind.EXTRACTION, "accepted")
                .item("URL", value)
                .pattern("url-pattern")
                .span(8, 61)
                .build();
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new PreparingListAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static Map<String, Object> eventFields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
