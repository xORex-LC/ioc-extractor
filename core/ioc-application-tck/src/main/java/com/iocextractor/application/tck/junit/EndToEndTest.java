package com.iocextractor.application.tck.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/** Marks a deterministic end-to-end suite; end-to-end suites are integration tests. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@IntegrationTest
@Tag("e2e")
public @interface EndToEndTest {
}
