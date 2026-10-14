package com.iocextractor.application.port.out.dataframeimport;

/** Validates one normalized import value through a named adapter-owned rule. */
@FunctionalInterface
public interface ImportValueValidatorRegistry {

    /** Returns whether the value satisfies the configured validation rule. */
    boolean isValid(String rule, String value);
}
