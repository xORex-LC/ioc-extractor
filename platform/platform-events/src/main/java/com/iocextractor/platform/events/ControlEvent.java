package com.iocextractor.platform.events;

/**
 * A service-owned fact used to nudge local coordination work.
 *
 * <p>Concrete events define their own payload and validation. Prefer bounded work references,
 * such as a completed slice ID, rather than business rows or file contents. This interface
 * defines no serialization format, delivery guarantee or recovery mechanism.</p>
 */
public interface ControlEvent {

    /**
     * Returns the metadata supplied by the event's producer.
     *
     * @return non-null metadata; publishers need not inspect it or propagate its IDs
     */
    ControlEventMetadata metadata();
}
