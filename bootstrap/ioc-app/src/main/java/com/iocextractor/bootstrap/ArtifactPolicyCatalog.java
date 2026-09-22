package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles configured artifact mutation policies once, outside the application core. */
final class ArtifactPolicyCatalog {

    private ArtifactPolicyCatalog() {
    }

    static Map<String, ArtifactWritePolicy> compile(IocProperties properties) {
        List<String> errors = new ArrayList<>();
        Map<String, ArtifactWritePolicy> result = compile(properties, errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return result;
    }

    static Map<String, ArtifactWritePolicy> compile(IocProperties properties, List<String> errors) {
        if (properties.sink() == null || properties.sink().artifacts() == null) {
            return Map.of();
        }
        Map<String, Set<String>> keys = new HashMap<>();
        if (properties.artifactIdentity() != null && properties.artifactIdentity().artifacts() != null) {
            for (var identity : properties.artifactIdentity().artifacts()) {
                if (identity != null && identity.name() != null && identity.keyColumns() != null) {
                    keys.put(identity.name(), new HashSet<>(identity.keyColumns()));
                }
            }
        }
        Map<String, ArtifactWritePolicy> result = new HashMap<>();
        List<IocProperties.Sink.Artifact> artifacts = properties.sink().artifacts();
        for (int i = 0; i < artifacts.size(); i++) {
            IocProperties.Sink.Artifact artifact = artifacts.get(i);
            if (artifact == null || artifact.name() == null || artifact.columns() == null) {
                continue;
            }
            String path = "ioc.sink.artifacts[" + i + "].write-policy";
            var configured = artifact.writePolicy();
            if (configured == null) {
                result.put(artifact.name(), ArtifactWritePolicy.legacy());
                continue;
            }
            Set<String> columns = new HashSet<>();
            for (var column : artifact.columns()) {
                if (column != null) {
                    columns.add(column.name());
                }
            }
            ArtifactWritePolicy.DuplicateSelection selection = selection(configured.duplicateSelection(),
                    path, errors);
            String selectionColumn = configured.selectionColumn();
            if (selection == ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST
                    && selectionColumn != null) {
                errors.add(path + ".selection-column is only valid for last-nonempty");
            }
            if (selection == ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY
                    && (selectionColumn == null || !columns.contains(selectionColumn))) {
                errors.add(path + ".selection-column must name an output column");
            }
            if (selectionColumn != null && (keys.getOrDefault(artifact.name(), Set.of()).contains(selectionColumn)
                    || "id".equals(selectionColumn))) {
                errors.add(path + ".selection-column cannot be an identity or public ID column");
            }
            Map<String, ArtifactWritePolicy.FieldUpdatePolicy> fields = new HashMap<>();
            if (configured.fields() != null) {
                for (int fieldIndex = 0; fieldIndex < configured.fields().size(); fieldIndex++) {
                    var field = configured.fields().get(fieldIndex);
                    String fieldPath = path + ".fields[" + fieldIndex + "]";
                    if (field == null || field.name() == null || !columns.contains(field.name())) {
                        errors.add(fieldPath + ".name must name an output column");
                        continue;
                    }
                    if (keys.getOrDefault(artifact.name(), Set.of()).contains(field.name())
                            || "id".equals(field.name())) {
                        errors.add(fieldPath + ".name cannot be an identity or public ID column");
                    }
                    if (!"latest-registered".equals(field.update())) {
                        errors.add(fieldPath + ".update must be latest-registered");
                    }
                    if (!"keep-existing".equals(field.empty())) {
                        errors.add(fieldPath + ".empty must be keep-existing");
                    }
                    if (fields.put(field.name(),
                            ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING) != null) {
                        errors.add(fieldPath + ".name is duplicated");
                    }
                }
            }
            if (selection != null && (selection != ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY
                    || selectionColumn != null && columns.contains(selectionColumn))) {
                result.put(artifact.name(), new ArtifactWritePolicy(selection, selectionColumn, fields));
            }
        }
        return Map.copyOf(result);
    }

    private static ArtifactWritePolicy.DuplicateSelection selection(String value,
                                                                     String path, List<String> errors) {
        if ("keep-first".equals(value)) {
            return ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST;
        }
        if ("last-nonempty".equals(value)) {
            return ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY;
        }
        errors.add(path + ".duplicate-selection must be keep-first or last-nonempty");
        return null;
    }
}
