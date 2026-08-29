package com.iocextractor.bootstrap;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Catalog of temporarily accepted configuration aliases and their value-free
 * operator migration notices.
 */
final class IocConfigurationMigrationCatalog {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(
                    "ioc.sync.endpoints[].smb.encrypt",
                    Pattern.compile("ioc\\.sync\\.endpoints\\[\\d+]\\.smb\\.encrypt"),
                    "ioc.sync.endpoints[].smb.encryption",
                    "CONFIG.LEGACY_SMB_ENCRYPTION"));

    private IocConfigurationMigrationCatalog() {
    }

    static Optional<Migration> find(String key) {
        return MIGRATIONS.stream()
                .filter(migration -> migration.keyPattern().matcher(key).matches())
                .findFirst();
    }

    static List<Migration> migrations() {
        return MIGRATIONS;
    }

    record Migration(String propertyPath,
                     Pattern keyPattern,
                     String replacement,
                     String diagnosticCode) {
    }
}
