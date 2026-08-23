package com.iocextractor.adapter.out.store.jdbc;

import java.sql.SQLException;
import java.util.List;

/** Typed pre-commit conflict that the later import planner can map to safe row diagnostics. */
final class PreferredExportSlotConflictException extends SQLException {

    private static final long serialVersionUID = 1L;

    enum Reason {
        DUPLICATE_REQUEST,
        SURVIVOR_MISMATCH
    }

    private final Reason reason;
    private final List<Long> lifecycleIds;

    PreferredExportSlotConflictException(Reason reason, List<Long> lifecycleIds, String message) {
        super(message);
        this.reason = reason;
        this.lifecycleIds = List.copyOf(lifecycleIds);
    }

    Reason reason() {
        return reason;
    }

    List<Long> lifecycleIds() {
        return lifecycleIds;
    }
}
