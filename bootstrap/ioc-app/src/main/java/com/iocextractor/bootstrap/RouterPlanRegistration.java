package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import java.util.List;
import java.util.Objects;

/** Already-admitted technical plans supplied by a future IOC processing binding. */
public record RouterPlanRegistration(List<PlanDescriptor> plans, OperationCatalog catalog,
                                     String fingerprint) {
    public RouterPlanRegistration {
        plans = List.copyOf(plans);
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("router registration requires at least one plan");
        }
        Objects.requireNonNull(catalog);
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("router registration requires a fingerprint");
        }
    }
}
