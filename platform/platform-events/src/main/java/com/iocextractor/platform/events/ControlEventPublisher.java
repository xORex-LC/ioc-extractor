package com.iocextractor.platform.events;

/**
 * Publish-only port for application control events.
 *
 * <p>The port intentionally has no subscription, routing, acknowledgement, redelivery or queue
 * contract. Consumers are driving adapters that translate events into use-case commands.</p>
 */
@FunctionalInterface
public interface ControlEventPublisher {

    /** Publishes one control event through the configured delivery adapter. */
    void publish(ControlEvent event);
}
