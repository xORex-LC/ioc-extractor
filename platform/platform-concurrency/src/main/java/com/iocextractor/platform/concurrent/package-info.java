/**
 * Framework-free concurrency primitives used by platform coordination code.
 *
 * <p>These contracts are intentionally separate from control events: admission and execution
 * ordering are concurrency concerns, not event-model concerns. Implementations are in-memory and
 * expose observer hooks for degradation telemetry, leaving durable recovery to callers.</p>
 */
package com.iocextractor.platform.concurrent;
