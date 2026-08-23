package com.iocextractor.application.port.out.dataframeimport;

/** Application boundary for startup-validated named value transforms. */
@FunctionalInterface
public interface ImportValueTransformRegistry {

    /**
     * Applies one compiled transform specification to one non-null value.
     *
     * @param specification validated name or name-with-argument
     * @param value source value
     * @return transformed non-null value
     */
    String transform(String specification, String value);
}
