package com.iocextractor.application.sync;

/**
 * Retry decision owned by the sync application policy, not by operator configuration.
 */
public enum RemoteErrorDisposition {
    /** Retry immediately within the current adapter operation. */
    RETRY_NOW,
    /** Stop the current cycle; the daemon/CLI caller may try on a later run. */
    RETRY_LATER,
    /** Do not retry automatically because the failure needs configuration/operator action. */
    FAIL
}
