package com.iocextractor.adapter.out.sink.csv;

import java.util.Objects;

/**
 * Located row-mapping rejection eligible for element-level continuation.
 *
 * <p>Only {@link ConfigurableRowMapper} creates this exception while translating
 * a {@link MappingValueException}. Other mapper failures remain run-level defects.
 */
public final class RowMappingException extends RuntimeException {

    /** Mapping extension point that rejected the current value. */
    public enum ComponentKind {
        PROVIDER("provider"),
        TRANSFORM("transform");

        private final String value;

        ComponentKind(String value) {
            this.value = value;
        }

        /** Returns the stable diagnostic representation. */
        public String value() {
            return value;
        }
    }

    private final String column;
    private final ComponentKind componentKind;
    private final String componentName;

    RowMappingException(String column,
                        ComponentKind componentKind,
                        String componentName,
                        MappingValueException cause) {
        super(message(column, componentKind, componentName, cause), cause);
        this.column = Objects.requireNonNull(column, "column");
        this.componentKind = Objects.requireNonNull(componentKind, "componentKind");
        this.componentName = Objects.requireNonNull(componentName, "componentName");
    }

    /** Returns the configured output column that could not be mapped. */
    public String column() {
        return column;
    }

    /** Returns whether a provider or transform rejected the value. */
    public ComponentKind componentKind() {
        return componentKind;
    }

    /** Returns the registered provider or transform name. */
    public String componentName() {
        return componentName;
    }

    private static String message(String column,
                                  ComponentKind componentKind,
                                  String componentName,
                                  MappingValueException cause) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(componentKind, "componentKind");
        Objects.requireNonNull(componentName, "componentName");
        Objects.requireNonNull(cause, "cause");
        return "Column '%s' rejected by %s '%s': %s".formatted(
                column, componentKind.value(), componentName, cause.getMessage());
    }
}
