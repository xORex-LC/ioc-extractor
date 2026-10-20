/**
 * Caller-selected masking for STRING log representations, independent of severity.
 * A full mask discards all source detail; the URL helper only transforms its
 * documented components. Neither operation detects secrets or sanitizes an entire
 * event, message, MDC context or Throwable.
 */
package com.iocextractor.platform.logging.redaction;
