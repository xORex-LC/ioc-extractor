package com.iocextractor.adapter.out.store.jdbc;

import java.util.List;

/**
 * Outcome of a versioned SQLite schema migration run.
 */
public record SchemaMigrationResult(int previousVersion,
                                    int currentVersion,
                                    List<Integer> appliedVersions) {

    public SchemaMigrationResult {
        appliedVersions = List.copyOf(appliedVersions);
    }
}
