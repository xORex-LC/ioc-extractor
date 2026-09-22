package com.iocextractor.application.artifact.policy;

/** Pure outcome for a field; origin-only advancement must not create a public revision. */
public enum FieldUpdateDecision {
    PRESERVE,
    CHANGE_PUBLIC_VALUE,
    ADVANCE_ORIGIN_ONLY
}
