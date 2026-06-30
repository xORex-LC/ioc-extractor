package com.iocextractor.platform.events;

/**
 * Observes event publication and dispatch for diagnostics/telemetry.
 *
 * <p>This is not a subscriber SPI. Implementations must avoid business side effects.</p>
 */
public interface ControlEventObserver {

    /** Called after an event has been accepted by the current publisher adapter. */
    void published(ControlEvent event);

    /** Called when the current publisher adapter fails before accepting an event. */
    void publishFailed(ControlEvent event, RuntimeException failure);

    /** Called when a delivery adapter starts dispatching an event to one handler. */
    void dispatching(ControlEvent event, String handlerName);

    /** Called when one handler fails while processing an event. */
    void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure);
}
