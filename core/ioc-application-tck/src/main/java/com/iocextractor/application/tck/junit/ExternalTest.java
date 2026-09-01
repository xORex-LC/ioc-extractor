package com.iocextractor.application.tck.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/** Marks a provisioned-only integration suite excluded from the offline universe. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@IntegrationTest
@Tag("external")
public @interface ExternalTest {
}
