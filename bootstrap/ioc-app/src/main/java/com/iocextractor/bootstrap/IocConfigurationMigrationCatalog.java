package com.iocextractor.bootstrap;

import com.iocextractor.config.boot.MigrationInspection;
import com.iocextractor.config.boot.MigrationInspector;
import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyMigration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog of temporarily accepted configuration aliases and their value-free
 * operator migration notices.
 */
final class IocConfigurationMigrationCatalog {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(
                    "ioc.sync.endpoints[].smb.encrypt",
                    "ioc.sync.endpoints[].smb.encryption",
                    "CONFIG.LEGACY_SMB_ENCRYPTION"));
    private static final List<PropertyMigration> DESCRIPTORS = MIGRATIONS.stream().map(Migration::descriptor).toList();

    private IocConfigurationMigrationCatalog() {
    }

    static Map<PropertyInspection.Declaration, Notice> notices(PropertyInspection inspection) {
        Map<PropertyInspection.Declaration, Notice> notices = new LinkedHashMap<>();
        for (MigrationInspection.Observation observation : MigrationInspector.inspect(inspection, DESCRIPTORS).observations()) {
            // The reporter emits a hint only after IOC's local matcher selects this exact path.
            // That preserves IOC's older support for custom environment source names, for which
            // the generic observer correctly reports an unqualified source convention.
            MigrationInspection.Match match = observation.matches().getFirst();
            Migration migration = MIGRATIONS.stream()
                    .filter(candidate -> candidate.propertyPath().equals(match.migration().path())).findFirst().orElseThrow();
            notices.put(observation.declaration(), new Notice(migration.diagnosticCode(), match.path(),
                    match.replacement().orElseThrow()));
        }
        return Map.copyOf(notices);
    }

    static List<Migration> migrations() {
        return MIGRATIONS;
    }

    record Migration(String propertyPath,
                     String replacement,
                     String diagnosticCode) {
        PropertyMigration descriptor() {
            return new PropertyMigration(propertyPath, PropertyMigration.Kind.DEPRECATED, Optional.of(replacement));
        }
    }

    record Notice(String diagnosticCode, String path, String replacement) { }
}
