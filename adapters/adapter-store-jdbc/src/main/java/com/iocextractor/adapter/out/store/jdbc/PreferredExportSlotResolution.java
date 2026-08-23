package com.iocextractor.adapter.out.store.jdbc;

/** Safe allocation result used by later import reporting without exposing row values. */
record PreferredExportSlotResolution(
        long lifecycleId,
        long requestedSlot,
        long assignedSlot,
        Outcome outcome) {

    enum Outcome {
        EXACT,
        OCCUPIED_FALLBACK,
        SURVIVOR_MATCH,
        SURVIVOR_MISMATCH_PRESERVED
    }
}
