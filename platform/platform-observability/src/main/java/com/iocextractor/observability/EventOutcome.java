package com.iocextractor.observability;

/**
 * Stable {@code event.outcome} values.
 */
public enum EventOutcome {
    SUCCESS(com.iocextractor.platform.logging.ecs.EventOutcome.SUCCESS),
    FAILURE(com.iocextractor.platform.logging.ecs.EventOutcome.FAILURE),
    UNKNOWN(com.iocextractor.platform.logging.ecs.EventOutcome.UNKNOWN);

    private final com.iocextractor.platform.logging.ecs.EventOutcome ecs;

    EventOutcome(com.iocextractor.platform.logging.ecs.EventOutcome ecs) {
        this.ecs = ecs;
    }

    /**
     * Returns the shared ECS outcome used when emitting this IOC event.
     * @return ECS outcome
     */
    public com.iocextractor.platform.logging.ecs.EventOutcome ecs() {
        return ecs;
    }

    public String value() {
        return ecs.value();
    }
}
