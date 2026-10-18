package com.iocextractor.platform.diagnostics.sink;

import com.iocextractor.platform.diagnostics.Diagnostic;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Keeps a delegate's delivery failure from aborting processing. Delivery and the
 * fallback callback run synchronously on the caller's thread; neither is retried.
 * Runtime exceptions from the fallback are also contained. JVM {@link Error}s
 * propagate unchanged. This wrapper owns no threads and cannot limit callback
 * latency. Its thread safety depends on the delegate and callback.
 */
public final class ResilientDiagnosticSink implements DiagnosticSink {
    private final DiagnosticSink delegate;
    private final BiConsumer<Diagnostic, RuntimeException> deliveryFailureHandler;

    /**
     * Creates a sink with an application-owned fallback, such as a logger.
     * @param delegate receives each non-null diagnostic once
     * @param deliveryFailureHandler receives the same diagnostic and delegate failure
     * @throws NullPointerException if either argument is null
     */
    public ResilientDiagnosticSink(DiagnosticSink delegate,
                                   BiConsumer<Diagnostic, RuntimeException> deliveryFailureHandler) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.deliveryFailureHandler = Objects.requireNonNull(deliveryFailureHandler, "deliveryFailureHandler");
    }

    /**
     * Calls the delegate and contains runtime delivery failures.
     * @param diagnostic occurrence to deliver
     * @throws NullPointerException if diagnostic is null, before invoking either callback
     */
    @Override
    public void emit(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        try {
            delegate.emit(diagnostic);
        } catch (RuntimeException failure) {
            try {
                deliveryFailureHandler.accept(diagnostic, failure);
            } catch (RuntimeException ignored) {
                // Reporting a failed report must not recurse or stop the caller.
            }
        }
    }
}
