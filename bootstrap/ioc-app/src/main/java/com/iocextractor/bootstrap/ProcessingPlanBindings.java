package com.iocextractor.bootstrap;

import java.util.Map;
import java.util.Optional;

/** Admitted IOC bindings; later flow attachments consume these without recompilation. */
record ProcessingPlanBindings(String documentPlan,
                              Map<String, ProcessingPlanCatalog.CompiledPlan> plans) {
    ProcessingPlanBindings {
        plans = Map.copyOf(plans);
    }

    Optional<ProcessingPlanCatalog.CompiledPlan> selectedDocumentPlan() {
        return Optional.ofNullable(documentPlan).map(plans::get);
    }
}
