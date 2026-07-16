package com.iocextractor.observability;

/**
 * Stable JSON scalar type of a structured log field.
 *
 * <p>The type is part of the downstream logging contract. Changing it for an
 * existing field requires an explicit index mapping migration.
 */
public enum LogValueType {
    STRING,
    LONG,
    BOOLEAN
}
