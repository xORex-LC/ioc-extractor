package com.iocextractor.platform.events;

/**
 * Framework-free application control event.
 *
 * <p>Control events are thin facts used for coordination. They carry metadata and bounded work
 * references, not business rows, file bytes or transport-specific messages.</p>
 */
public interface ControlEvent {

    /** Returns immutable event metadata used for tracing, versioning and future delivery headers. */
    ControlEventMetadata metadata();
}
