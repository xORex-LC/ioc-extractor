package com.iocextractor.bootstrap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a typed configuration field that is accepted only during a bounded
 * expand/contract compatibility window and must not appear in fresh templates.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
@interface ConfigurationCompatibilityAlias {
}
