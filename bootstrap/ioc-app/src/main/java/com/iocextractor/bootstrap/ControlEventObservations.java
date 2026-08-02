package com.iocextractor.bootstrap;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventObserver;

/** Preserves handler failures while delivering best-effort control-event observations. */
final class ControlEventObservations {

    private ControlEventObservations() {
    }

    static void dispatchFailed(ControlEventObserver observer,
                               ControlEvent event,
                               String handlerName,
                               RuntimeException handlerFailure) {
        try {
            observer.dispatchFailed(event, handlerName, handlerFailure);
        } catch (RuntimeException observationFailure) {
            if (observationFailure != handlerFailure) {
                handlerFailure.addSuppressed(observationFailure);
            }
        }
    }
}
