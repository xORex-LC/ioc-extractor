package com.iocextractor.platform.events;

/**
 * Publish-only port for application control events.
 *
 * <p>The implementation defines delivery timing, exception handling, ordering and thread
 * safety. An ordinary return alone does not prove that any handler ran or durably completed.
 * A failed call may follow partial delivery, so callers must not infer that retrying is safe.
 * Services own their handlers, durable state and recovery.</p>
 */
@FunctionalInterface
public interface ControlEventPublisher {

    /**
     * Submits one event to this publisher.
     *
     * @param event non-null service-owned event
     * @throws NullPointerException if {@code event} is null
     */
    void publish(ControlEvent event);
}
