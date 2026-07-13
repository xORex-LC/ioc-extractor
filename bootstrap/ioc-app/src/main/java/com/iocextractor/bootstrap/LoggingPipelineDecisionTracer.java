package com.iocextractor.bootstrap;

import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Locale;
import java.util.function.Consumer;

/** SLF4J adapter for explicitly enabled structured per-item pipeline decisions. */
public final class LoggingPipelineDecisionTracer implements PipelineDecisionTracer {

    private static final int SHORT_HASH_LENGTH = 12;
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(
            LoggingPipelineDecisionTracer::sha256);

    private final Logger logger;
    private final boolean configuredEnabled;
    private final Consumer<PipelineItemDecision> renderer;

    /** Creates a tracer gated by both configuration and the logger TRACE level. */
    public LoggingPipelineDecisionTracer(Logger logger, boolean configuredEnabled) {
        this(logger, configuredEnabled, decision -> render(logger, decision));
    }

    LoggingPipelineDecisionTracer(Logger logger,
                                  boolean configuredEnabled,
                                  Consumer<PipelineItemDecision> renderer) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.configuredEnabled = configuredEnabled;
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public boolean isEnabled() {
        return configuredEnabled && logger.isTraceEnabled();
    }

    @Override
    public void trace(PipelineItemDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (isEnabled()) {
            try {
                renderer.accept(decision);
            } catch (RuntimeException ignored) {
                // Operational tracing must not change the processing outcome or recurse into logging.
            }
        }
    }

    private static void render(Logger logger, PipelineItemDecision decision) {
        var event = LogEvents.trace(logger)
                .action(EventAction.PIPELINE_ITEM_DECISION)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.IOC_DECISION_KIND, decision.kind().name().toLowerCase(Locale.ROOT))
                .field(LogField.IOC_DECISION_OUTCOME, decision.outcome())
                .field(LogField.IOC_ITEM_IDENTITY, identity(decision));
        field(event, LogField.IOC_INDICATOR_TYPE, decision.indicatorType());
        field(event, LogField.IOC_ITEM_VALUE, redactQuery(decision.value()));
        field(event, LogField.IOC_DECISION_RULE, decision.rule());
        field(event, LogField.IOC_DECISION_PATTERN, decision.pattern());
        field(event, LogField.IOC_DECISION_RESULT, decision.result());
        field(event, LogField.IOC_SPAN_START, decision.spanStart());
        field(event, LogField.IOC_SPAN_END, decision.spanEnd());
        field(event, LogField.IOC_ARTIFACT_NAME, decision.artifact());
        event.message("pipeline item decision").log();
    }

    private static void field(com.iocextractor.observability.logging.LogEvent event,
                              LogField field,
                              Object value) {
        if (value != null) {
            event.field(field, value);
        }
    }

    private static String identity(PipelineItemDecision decision) {
        if (decision.identity() != null && !decision.identity().isBlank()) {
            return decision.identity();
        }
        String value = Objects.requireNonNullElse(decision.value(), "");
        String type = Objects.requireNonNullElse(decision.indicatorType(), "item").toLowerCase();
        String span = decision.spanStart() == null
                ? ""
                : "@" + decision.spanStart() + ":" + decision.spanEnd();
        return type + ":" + shortHash(value) + span;
    }

    private static String redactQuery(String value) {
        if (value == null) {
            return null;
        }
        int query = value.indexOf('?');
        if (query < 0) {
            return value;
        }
        int fragment = value.indexOf('#', query);
        return fragment < 0
                ? value.substring(0, query) + "?<redacted>"
                : value.substring(0, query) + "?<redacted>" + value.substring(fragment);
    }

    private static String shortHash(String value) {
        byte[] digest = SHA_256.get().digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest, 0, SHORT_HASH_LENGTH / 2);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
