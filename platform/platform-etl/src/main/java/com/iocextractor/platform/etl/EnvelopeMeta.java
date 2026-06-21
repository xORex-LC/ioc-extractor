package com.iocextractor.platform.etl;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable metadata that travels with a pipeline envelope.
 *
 * <p>The ETL kernel owns only generic correlation and stage metadata. Concrete
 * pipelines add application-specific values through {@code attributes}.
 *
 * @param runId one pipeline run id
 * @param sourceId stable source id within the run
 * @param stage current stage
 * @param createdAt metadata creation timestamp
 * @param attributes extension attributes for pipeline-specific metadata
 */
public record EnvelopeMeta(
        String runId,
        String sourceId,
        StageId stage,
        Instant createdAt,
        Map<String, Object> attributes
) {

    /**
     * Creates metadata with defensive attribute copying.
     */
    public EnvelopeMeta {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(createdAt, "createdAt");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * Creates initial metadata for one source.
     *
     * @param runId run id
     * @param sourceId stable source id
     * @param clock timestamp clock
     * @return initial metadata
     */
    public static EnvelopeMeta initial(String runId, String sourceId, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return new EnvelopeMeta(
                runId,
                sourceId,
                StageId.INITIAL,
                clock.instant(),
                Map.of());
    }

    /**
     * Returns metadata for another stage.
     *
     * @param nextStage stage to set
     * @return updated metadata
     */
    public EnvelopeMeta atStage(StageId nextStage) {
        return new EnvelopeMeta(runId, sourceId, nextStage, createdAt, attributes);
    }

    /**
     * Returns metadata with one additional attribute.
     *
     * @param key attribute key
     * @param value attribute value
     * @return updated metadata
     */
    public EnvelopeMeta withAttribute(String key, Object value) {
        var next = new LinkedHashMap<>(attributes);
        next.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return new EnvelopeMeta(runId, sourceId, stage, createdAt, next);
    }

    /**
     * Returns metadata with additional attributes.
     *
     * @param additional attributes to merge
     * @return updated metadata
     */
    public EnvelopeMeta withAttributes(Map<String, ?> additional) {
        var next = new LinkedHashMap<>(attributes);
        next.putAll(Objects.requireNonNull(additional, "additional"));
        return new EnvelopeMeta(runId, sourceId, stage, createdAt, next);
    }

    /**
     * Returns a boolean attribute value, or a default when absent or not boolean.
     *
     * @param key attribute key
     * @param defaultValue value to return when absent
     * @return attribute value
     */
    public boolean booleanAttribute(String key, boolean defaultValue) {
        Object value = attributes.get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    /**
     * Returns a string attribute value, or {@code null} when absent.
     *
     * @param key attribute key
     * @return string value or {@code null}
     */
    public String stringAttribute(String key) {
        Object value = attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
