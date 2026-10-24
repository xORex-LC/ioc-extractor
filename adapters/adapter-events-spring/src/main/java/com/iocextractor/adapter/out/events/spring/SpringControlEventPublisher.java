package com.iocextractor.adapter.out.events.spring;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventObserver;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;

/**
 * Publishes local control events through a service-supplied Spring publisher.
 *
 * <p>Ordinary runtime publication failures are observed and contained. Errors propagate,
 * including an Error directly wrapped by Spring in an {@link UndeclaredThrowableException}.
 * A failed publication can follow partial delivery; there is no automatic retry.</p>
 *
 * <p>This adapter installs no beans, listeners, multicaster, executor or error handler.
 * Execution timing, listener isolation and shutdown belong to the supplied publisher.
 * Failures already handled by a service error handler, or occurring after an asynchronous
 * hand-off, cannot be reported by this call. Concurrent use requires a thread-safe publisher
 * and observer; the adapter retains no per-publication state.</p>
 */
public final class SpringControlEventPublisher implements ControlEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final ControlEventObserver observer;
    private final Logger fallbackLogger;

    /**
     * Creates an adapter without changing the supplied publisher's configuration.
     *
     * @param publisher Spring publisher owned and configured by the service
     * @param observer service-owned publication observer; dispatch callbacks are not invoked here
     * @throws NullPointerException if either argument is null
     */
    public SpringControlEventPublisher(ApplicationEventPublisher publisher, ControlEventObserver observer) {
        this(publisher, observer, LoggerFactory.getLogger(SpringControlEventPublisher.class));
    }

    SpringControlEventPublisher(ApplicationEventPublisher publisher, ControlEventObserver observer,
                               Logger fallbackLogger) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.fallbackLogger = Objects.requireNonNull(fallbackLogger, "fallbackLogger");
    }

    /**
     * Attempts publication once, then reports the outcome to the observer.
     *
     * <p>A normal Spring return triggers {@code published}; a runtime failure triggers
     * {@code publishFailed} with the original exception. Runtime observer failures use a
     * best-effort SLF4J WARN. After a failed publication that WARN retains the publication
     * failure as primary, adding a distinct observer failure as suppressed when supported.
     * Runtime failures of the fallback logger are contained without another logging attempt.
     * Errors from publication, observation or fallback logging propagate.</p>
     *
     * @param event non-null event; its metadata and payload are not inspected or copied
     * @throws NullPointerException if {@code event} is null, before delivery or observation
     */
    @Override
    public void publish(ControlEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException failure) {
            if (failure instanceof UndeclaredThrowableException wrapper
                    && wrapper.getUndeclaredThrowable() instanceof Error fatal) {
                throw fatal;
            }
            observePublishFailed(event, failure);
            return;
        }
        observePublished(event);
    }

    private void observePublished(ControlEvent event) {
        try {
            observer.published(event);
        } catch (RuntimeException failure) {
            warn("control event observer failed after publication", failure);
        }
    }

    private void observePublishFailed(ControlEvent event, RuntimeException publishFailure) {
        try {
            observer.publishFailed(event, publishFailure);
        } catch (RuntimeException observerFailure) {
            if (observerFailure != publishFailure) {
                publishFailure.addSuppressed(observerFailure);
            }
            warn("control event observer failed after publication failure", publishFailure);
        }
    }

    private void warn(String message, RuntimeException failure) {
        try {
            fallbackLogger.warn(message, failure);
        } catch (RuntimeException ignored) {
            // This is the last reporting path. Retrying here could recurse through a broken provider.
        }
    }
}
