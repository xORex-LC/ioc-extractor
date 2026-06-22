package com.iocextractor.application.port.in.maintenance;

import java.util.Map;

/**
 * Outcome of one retention sweep.
 *
 * @param scanned        total entries inspected across all targets
 * @param reaped         total entries deleted/archived
 * @param reapedByTarget per-target reaped counts (keyed by target name)
 */
public record RetentionResult(int scanned, int reaped, Map<String, Integer> reapedByTarget) {

    public RetentionResult {
        reapedByTarget = Map.copyOf(reapedByTarget);
    }

    public static RetentionResult empty() {
        return new RetentionResult(0, 0, Map.of());
    }
}
