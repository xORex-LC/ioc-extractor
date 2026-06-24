package com.iocextractor.adapter.out.store.jdbc;

import java.util.List;

/**
 * Additive dataframe schema plan. Destructive drift is rejected before a plan is
 * returned, so every item here is safe to apply.
 */
public record DataframeSchemaPlan(List<DataframeSchemaChange> changes) {

    public DataframeSchemaPlan {
        changes = List.copyOf(changes);
    }

    public boolean empty() {
        return changes.isEmpty();
    }
}
