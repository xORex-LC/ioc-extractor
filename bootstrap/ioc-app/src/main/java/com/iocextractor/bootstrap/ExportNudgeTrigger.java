package com.iocextractor.bootstrap;

/** Local trigger that asks export scheduling to re-check durable cadence soon. */
@FunctionalInterface
interface ExportNudgeTrigger {

    /** Schedules an asynchronous cadence check when the current export policy allows it. */
    void nudge();
}
