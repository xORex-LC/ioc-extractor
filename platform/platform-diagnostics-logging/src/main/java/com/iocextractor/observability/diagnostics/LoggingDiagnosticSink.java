package com.iocextractor.observability.diagnostics;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.IocDiagnosticCode;
import com.iocextractor.observability.MdcScope;
import com.iocextractor.diagnostics.render.DiagnosticRenderer;
import com.iocextractor.platform.diagnostics.sink.DiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Emits diagnostics as operational log events.
 */
public final class LoggingDiagnosticSink implements DiagnosticSink {

    private final Logger logger;
    private final DiagnosticRenderer renderer;

    public LoggingDiagnosticSink(Logger logger, DiagnosticRenderer renderer) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public void emit(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        // A generic code has no IOC category. Hide an ambient one too, otherwise
        // the logging backend could attach a category from an unrelated scope.
        try (var scope = MdcScope.open()) {
            scope.hide(LogField.IOC_DIAGNOSTIC_CATEGORY);
            var event = event(diagnostic)
                    .action(EventAction.DIAGNOSTIC_EMIT)
                    .outcome(EventOutcome.UNKNOWN)
                    .field(LogField.IOC_DIAGNOSTIC_CODE, diagnostic.code().id())
                    .field(LogField.IOC_DIAGNOSTIC_SEVERITY, diagnostic.severity());
            if (diagnostic.code() instanceof IocDiagnosticCode code) {
                event.field(LogField.IOC_DIAGNOSTIC_CATEGORY, code.category());
            }
            event.message(renderer.render(diagnostic)).log(diagnostic.cause().orElse(null));
        }
    }

    private LogEvent event(Diagnostic diagnostic) {
        return switch (diagnostic.severity()) {
            case FATAL, ERROR -> LogEvents.error(logger);
            case WARN -> LogEvents.warn(logger);
            case INFO -> LogEvents.info(logger);
            case DEBUG -> LogEvents.debug(logger);
            case TRACE -> LogEvents.trace(logger);
        };
    }
}
