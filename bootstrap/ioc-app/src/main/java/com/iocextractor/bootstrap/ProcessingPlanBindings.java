package com.iocextractor.bootstrap;

import java.util.Map;

/** Admitted named plans with one mandatory document selection. */
record ProcessingPlanBindings(String documentPlan,
                              Map<String, ProcessingPlanCatalog.CompiledPlan> plans) {
    ProcessingPlanBindings {
        plans = Map.copyOf(plans);
    }

    ProcessingPlanCatalog.CompiledPlan requireDocumentPlan() {
        var selected = documentPlan == null ? null : plans.get(documentPlan);
        if (selected == null) {
            throw new IllegalStateException("CONFIG.REGISTRY document-plan must reference an admitted plan");
        }
        return selected;
    }
}
