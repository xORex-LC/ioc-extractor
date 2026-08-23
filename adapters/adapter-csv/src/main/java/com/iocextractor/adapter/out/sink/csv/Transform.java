package com.iocextractor.adapter.out.sink.csv;

/**
 * Transforms a cell value ({@code transform: name} or {@code name:arg}).
 * Thin and reusable; new transform = new class registered by key.
 *
 * <p>An implementation may throw {@link MappingValueException} only for an
 * expected input-dependent rejection of the current value. Configuration,
 * registry and programming defects must use their natural exception type.
 */
public interface Transform {

    /**
     * Applies the configured transformation.
     *
     * @param value non-null input cell value
     * @param arg optional transform argument
     * @return transformed cell value
     * @throws MappingValueException when this particular value cannot be transformed
     */
    String apply(String value, String arg);
}
