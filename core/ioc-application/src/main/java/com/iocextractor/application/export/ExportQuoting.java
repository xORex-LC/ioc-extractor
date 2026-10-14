package com.iocextractor.application.export;

/** CSV quoting policy, applied independently to headers and data cells. */
public enum ExportQuoting {
    /** Quote every non-null cell; the null marker remains unquoted. */
    ALL,
    /** Quote only cells that need protection, including text equal to the null marker. */
    MINIMAL,
    /** Reject cells that cannot be represented without quoting or escaping. */
    NONE
}
