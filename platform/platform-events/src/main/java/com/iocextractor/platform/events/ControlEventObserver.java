package com.iocextractor.platform.events;

/**
 * Observes event publication and dispatch for diagnostics/telemetry.
 *
 * <p>Callbacks report a publication call or an individual dispatch attempt. They are not
 * subscriptions and must not perform business work. The caller decides how to handle an
 * observer exception; this interface does not wrap callbacks or isolate handlers.</p>
 */
public interface ControlEventObserver {

    /**
     * Reports that the underlying publication call returned normally.
     *
     * <p>This does not confirm completion of asynchronous work or delivery to every handler.</p>
     *
     * @param event non-null event passed to the publisher
     */
    void published(ControlEvent event);

    /**
     * Reports a runtime failure of the underlying publication call.
     *
     * <p>Some handlers may already have acted before the call failed. This signal does not
     * establish that nothing was delivered or that the event can be safely retried.</p>
     *
     * @param event non-null event passed to the publisher
     * @param failure non-null publication failure
     */
    void publishFailed(ControlEvent event, RuntimeException failure);

    /**
     * Reports an attempt to invoke one service-owned handler.
     *
     * @param event non-null event being dispatched
     * @param handlerName non-null, non-blank service-defined handler name
     */
    void dispatching(ControlEvent event, String handlerName);

    /**
     * Reports a runtime failure from one handler; other handlers may have acted already.
     *
     * @param event non-null event being dispatched
     * @param handlerName non-null, non-blank service-defined handler name
     * @param failure non-null handler failure
     */
    void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure);
}
