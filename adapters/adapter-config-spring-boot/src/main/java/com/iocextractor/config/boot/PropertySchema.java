package com.iocextractor.config.boot;

import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

import java.util.Objects;

/**
 * The namespace and record tree whose declarations an inspector should examine.
 * This describes names, not conversion rules or constraints on values.
 *
 * @param prefix one canonical, lower-case namespace segment, such as {@code collector}
 * @param rootType an ordinary record with ASCII lower camel case field names
 */
public record PropertySchema(String prefix, Class<?> rootType) {

    /**
     * Checks the root contract. Unsupported nested shapes are reported by inspection.
     *
     * @throws IllegalArgumentException if the prefix is not one canonical segment or the root is not a record
     * @throws NullPointerException if either argument is null
     */
    public PropertySchema {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(rootType, "rootType");
        ConfigurationPropertyName name = ConfigurationPropertyName.ofIfValid(prefix);
        if (name == null || name.getNumberOfElements() != 1 || name.hasIndexedElement() || !rootType.isRecord()) {
            throw new IllegalArgumentException("Use one canonical prefix and a record root");
        }
    }
}
