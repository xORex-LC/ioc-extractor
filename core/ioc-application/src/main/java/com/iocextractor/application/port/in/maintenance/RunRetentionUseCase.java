package com.iocextractor.application.port.in.maintenance;

/**
 * Driving port: run one retention sweep across all configured targets. Invoked by
 * the daemon's scheduled maintenance trigger.
 */
public interface RunRetentionUseCase {

    /** Sweep every configured target once and report what was reaped. */
    RetentionResult run();
}
