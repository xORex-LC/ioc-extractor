package com.iocextractor.application.maintenance;

/**
 * What to do with an entry that a {@link RetentionPolicy} marks as expired.
 */
public enum RetentionAction {

    /** Remove the entry permanently. */
    DELETE,

    /** Move the entry into the target's archive directory. */
    ARCHIVE
}
