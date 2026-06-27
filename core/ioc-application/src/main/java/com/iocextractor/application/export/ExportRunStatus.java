package com.iocextractor.application.export;

import java.util.EnumSet;
import java.util.Set;

/** Durable checkpoints of the local artifact-formation saga. */
public enum ExportRunStatus {
    STARTED,
    STAGED,
    AVAILABLE,
    COMPLETED,
    SKIPPED,
    FAILED;

    /** Returns whether this status can advance directly to {@code next}. */
    public boolean canTransitionTo(ExportRunStatus next) {
        return successors().contains(next);
    }

    /** Returns whether no forward transition is permitted from this status. */
    public boolean terminal() {
        return successors().isEmpty();
    }

    private Set<ExportRunStatus> successors() {
        return switch (this) {
            case STARTED -> EnumSet.of(STAGED, SKIPPED, FAILED);
            case STAGED -> EnumSet.of(AVAILABLE, FAILED);
            case AVAILABLE -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, SKIPPED, FAILED -> EnumSet.noneOf(ExportRunStatus.class);
        };
    }
}
