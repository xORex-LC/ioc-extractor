package com.iocextractor.application.pipeline;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable metadata that travels with a pipeline envelope.
 *
 * @param runId one extraction run id
 * @param sourceId stable source id within the run
 * @param sourcePath source path
 * @param stage current stage
 * @param createdAt metadata creation timestamp
 * @param attributes extension attributes for technical metadata
 */
public record EnvelopeMeta(
        String runId,
        String sourceId,
        Path sourcePath,
        StageName stage,
        Instant createdAt,
        Map<String, Object> attributes
) {

    /** Attribute key carrying the inbound dry-run flag. */
    public static final String DRY_RUN = "dryRun";

    /** Attribute key carrying the observability mode. */
    public static final String MODE = "mode";

    /**
     * Creates metadata with defensive attribute copying.
     */
    public EnvelopeMeta {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sourceId, "sourceId");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath").toAbsolutePath().normalize();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(createdAt, "createdAt");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * Creates initial metadata for one source.
     *
     * @param runId run id
     * @param sourcePath source path
     * @param dryRun dry-run flag
     * @param clock timestamp clock
     * @return initial metadata
     */
    public static EnvelopeMeta initial(String runId, Path sourcePath, boolean dryRun, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        var normalized = Objects.requireNonNull(sourcePath, "sourcePath").toAbsolutePath().normalize();
        return new EnvelopeMeta(
                runId,
                normalized.toString(),
                normalized,
                StageName.INITIAL,
                clock.instant(),
                Map.of(DRY_RUN, dryRun));
    }

    /**
     * Returns metadata for another stage.
     *
     * @param nextStage stage to set
     * @return updated metadata
     */
    public EnvelopeMeta atStage(StageName nextStage) {
        return new EnvelopeMeta(runId, sourceId, sourcePath, nextStage, createdAt, attributes);
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
        return new EnvelopeMeta(runId, sourceId, sourcePath, stage, createdAt, next);
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
}
