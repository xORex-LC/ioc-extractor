package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;
import java.util.Optional;

/**
 * Persisted singleton controlling one-way validity activation.
 *
 * @param version optimistic concurrency version
 * @param activationState durable activation state
 * @param policyFingerprint configured policy identity once activation starts
 * @param activatedAt completion time once active
 */
public record LifecycleControlState(long version,
                                    LifecycleActivationState activationState,
                                    Optional<String> policyFingerprint,
                                    Optional<EffectiveTime> activatedAt) {

    /** Enforces the legal data shape for each activation state. */
    public LifecycleControlState {
        Objects.requireNonNull(activationState, "activationState");
        policyFingerprint = Objects.requireNonNull(policyFingerprint, "policyFingerprint");
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("Lifecycle control version must not be negative");
        }
        policyFingerprint.ifPresent(value -> requireText(value, "policyFingerprint"));
        switch (activationState) {
            case DISABLED_COMPATIBLE -> {
                if (policyFingerprint.isPresent() || activatedAt.isPresent()) {
                    throw new IllegalArgumentException("Disabled lifecycle state cannot carry activation facts");
                }
            }
            case ACTIVATING -> {
                if (policyFingerprint.isEmpty() || activatedAt.isPresent()) {
                    throw new IllegalArgumentException("Activating state requires only a policy fingerprint");
                }
            }
            case ACTIVE -> {
                if (policyFingerprint.isEmpty() || activatedAt.isEmpty()) {
                    throw new IllegalArgumentException("Active state requires policy and activation time");
                }
            }
        }
    }

    /** Creates the initial compatibility state. */
    public static LifecycleControlState disabledCompatible() {
        return new LifecycleControlState(
                0, LifecycleActivationState.DISABLED_COMPATIBLE, Optional.empty(), Optional.empty());
    }

    /** Begins explicit activation or returns the matching in-progress/active state. */
    public LifecycleControlState beginActivation(String fingerprint) {
        fingerprint = requireText(fingerprint, "fingerprint");
        if (activationState != LifecycleActivationState.DISABLED_COMPATIBLE) {
            if (!policyFingerprint.orElseThrow().equals(fingerprint)) {
                throw new IllegalStateException("Lifecycle policy fingerprint cannot change after activation starts");
            }
            return this;
        }
        return new LifecycleControlState(
                Math.incrementExact(version),
                LifecycleActivationState.ACTIVATING,
                Optional.of(fingerprint),
                Optional.empty());
    }

    /** Completes an in-progress activation; repeated completion is idempotent. */
    public LifecycleControlState completeActivation(EffectiveTime completionTime) {
        Objects.requireNonNull(completionTime, "completionTime");
        if (activationState == LifecycleActivationState.DISABLED_COMPATIBLE) {
            throw new IllegalStateException("Lifecycle activation has not started");
        }
        if (activationState == LifecycleActivationState.ACTIVE) {
            return this;
        }
        return new LifecycleControlState(
                Math.incrementExact(version),
                LifecycleActivationState.ACTIVE,
                policyFingerprint,
                Optional.of(completionTime));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
