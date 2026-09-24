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
        Map<String, Set<String>> keys = identityKeys(properties);
        Map<String, ArtifactWritePolicy> result = new HashMap<>();
        List<IocProperties.Sink.Artifact> artifacts = properties.sink().artifacts();
        for (int i = 0; i < artifacts.size(); i++) {
            IocProperties.Sink.Artifact artifact = artifacts.get(i);
            ArtifactWritePolicy policy = compileArtifact(artifact, i, keys, errors);
            if (policy != null) {
                result.put(artifact.name(), policy);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Set<String>> identityKeys(IocProperties properties) {
        Map<String, Set<String>> keys = new HashMap<>();
        if (properties.artifactIdentity() == null || properties.artifactIdentity().artifacts() == null) {
            return keys;
        }
        for (var identity : properties.artifactIdentity().artifacts()) {
            if (identity != null && identity.name() != null && identity.keyColumns() != null) {
                keys.put(identity.name(), new HashSet<>(identity.keyColumns()));
            }
        }
        return keys;
    }

    private static ArtifactWritePolicy compileArtifact(IocProperties.Sink.Artifact artifact,
                                                       int index,
                                                       Map<String, Set<String>> keys,
                                                       List<String> errors) {
        if (artifact == null || artifact.name() == null || artifact.columns() == null) {
            return null;
        }
        var configured = artifact.writePolicy();
        if (configured == null) {
            return ArtifactWritePolicy.legacy();
        }
        String path = "ioc.sink.artifacts[" + index + "].write-policy";
        Set<String> columns = columnNames(artifact);
        Set<String> identity = keys.getOrDefault(artifact.name(), Set.of());
        ArtifactWritePolicy.DuplicateSelection selection = selection(
                configured.duplicateSelection(), path, errors);
        String selectionColumn = configured.selectionColumn();
        validateSelection(selection, selectionColumn, columns, identity, path, errors);
        Map<String, ArtifactWritePolicy.FieldUpdatePolicy> fields = compileFields(
                configured.fields(), columns, identity, path, errors);
        if ((selection == ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY || !fields.isEmpty())
                && identity.isEmpty()) {
            errors.add(path + " requires a nonempty artifact identity");
        }
        return validSelection(selection, selectionColumn, columns)
                ? new ArtifactWritePolicy(selection, selectionColumn, fields) : null;
    }

    private static Set<String> columnNames(IocProperties.Sink.Artifact artifact) {
        Set<String> columns = new HashSet<>();
        for (var column : artifact.columns()) {
            if (column != null) {
                columns.add(column.name());
            }
        }
        return columns;
    }

    private static void validateSelection(ArtifactWritePolicy.DuplicateSelection selection,
                                          String selectionColumn,
                                          Set<String> columns,
                                          Set<String> identity,
                                          String path,
                                          List<String> errors) {
        if (selection == ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST && selectionColumn != null) {
            errors.add(path + ".selection-column is only valid for last-nonempty");
        }
        if (selection == ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY
                && (selectionColumn == null || !columns.contains(selectionColumn))) {
            errors.add(path + ".selection-column must name an output column");
        }
        if (selectionColumn != null && (identity.contains(selectionColumn) || "id".equals(selectionColumn))) {
            errors.add(path + ".selection-column cannot be an identity or public ID column");
        }
    }

    private static Map<String, ArtifactWritePolicy.FieldUpdatePolicy> compileFields(
            List<IocProperties.Sink.Artifact.WritePolicy.Field> configured,
            Set<String> columns,
            Set<String> identity,
            String path,
            List<String> errors) {
        Map<String, ArtifactWritePolicy.FieldUpdatePolicy> fields = new HashMap<>();
        if (configured == null) {
            return fields;
        }
        for (int i = 0; i < configured.size(); i++) {
            compileField(configured.get(i), i, columns, identity, path, fields, errors);
        }
        return fields;
    }

    private static void compileField(IocProperties.Sink.Artifact.WritePolicy.Field field,
                                     int index,
                                     Set<String> columns,
                                     Set<String> identity,
                                     String path,
                                     Map<String, ArtifactWritePolicy.FieldUpdatePolicy> fields,
                                     List<String> errors) {
        String fieldPath = path + ".fields[" + index + "]";
        if (field == null || field.name() == null || !columns.contains(field.name())) {
            errors.add(fieldPath + ".name must name an output column");
            return;
        }
        if (identity.contains(field.name()) || "id".equals(field.name())) {
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

    private static boolean validSelection(ArtifactWritePolicy.DuplicateSelection selection,
                                          String selectionColumn,
                                          Set<String> columns) {
        return selection != null && (selection != ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY
                || selectionColumn != null && columns.contains(selectionColumn));
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
