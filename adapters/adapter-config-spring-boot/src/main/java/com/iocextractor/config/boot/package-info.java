/**
 * Value-free inspection of a service-owned Spring Boot record schema and its declarations.
 * {@link com.iocextractor.config.boot.PropertyInspector} returns immutable observations;
 * {@link com.iocextractor.config.boot.MigrationInspector} matches explicit old-name
 * descriptors against that snapshot without revisiting property sources.
 * The service owns semantic validation, rendering, severity and startup enforcement.
 * No source label, property value or Spring context is retained in a result.
 */
package com.iocextractor.config.boot;
