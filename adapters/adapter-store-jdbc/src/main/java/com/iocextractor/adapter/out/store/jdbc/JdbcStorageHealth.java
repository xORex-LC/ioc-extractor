package com.iocextractor.adapter.out.store.jdbc;

/**
 * Snapshot returned by {@link JdbcStorageHealthProbe}. It is intentionally a
 * storage VO, not an Actuator type, so bootstrap remains the only web/Actuator
 * integration point.
 *
 * @param healthy whether the storage role satisfies connectivity and SQLite policy checks
 * @param dbRole logical storage role
 * @param userVersion SQLite {@code user_version}, when readable
 * @param foreignKeys whether {@code PRAGMA foreign_keys} is enabled
 * @param journalMode current {@code PRAGMA journal_mode}
 * @param quickCheck result of {@code PRAGMA quick_check}
 * @param error failure detail, when the probe could not complete
 */
public record JdbcStorageHealth(boolean healthy,
                                String dbRole,
                                Integer userVersion,
                                Boolean foreignKeys,
                                String journalMode,
                                String quickCheck,
                                String error) {
}
