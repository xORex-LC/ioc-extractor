package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Maps Spring-bound properties to the framework-free import contract boundary. */
final class DataframeImportPropertyMapper {

    private DataframeImportPropertyMapper() {
    }

    static DataframeImportCatalogDraft draft(IocProperties.DataframeImport properties) {
        if (properties == null) {
            return null;
        }
        return new DataframeImportCatalogDraft(
                properties.enabled(),
                map(properties.sources(), DataframeImportPropertyMapper::source),
                map(properties.authorityProfiles(), DataframeImportPropertyMapper::authority),
                map(properties.contracts(), DataframeImportPropertyMapper::contract));
    }

    static DataframeImportCatalogEnvironment environment(IocProperties properties) {
        Map<String, MutableArtifactSchema> schemas = new LinkedHashMap<>();
        if (properties.sink() != null && properties.sink().artifacts() != null) {
            for (IocProperties.Sink.Artifact artifact : properties.sink().artifacts()) {
                if (artifact == null || !hasText(artifact.name())) {
                    continue;
                }
                MutableArtifactSchema schema = schemas.computeIfAbsent(artifact.name(), ignored -> new MutableArtifactSchema());
                schema.hasExternalId = artifact.hasPublicIdColumn();
                if (artifact.columns() != null) {
                    artifact.columns().stream().filter(java.util.Objects::nonNull)
                            .map(IocProperties.Sink.Artifact.Column::name).filter(DataframeImportPropertyMapper::hasText)
                            .forEach(schema.columns::add);
                }
            }
        }
        if (properties.artifactIdentity() != null && properties.artifactIdentity().artifacts() != null) {
            for (IocProperties.ArtifactIdentity.Artifact artifact : properties.artifactIdentity().artifacts()) {
                if (artifact == null || !hasText(artifact.name())) {
                    continue;
                }
                MutableArtifactSchema schema = schemas.computeIfAbsent(artifact.name(), ignored -> new MutableArtifactSchema());
                schema.recordKey = artifact.recordKey();
                if (artifact.matchKeys() == null) {
                    continue;
                }
                artifact.matchKeys().stream().filter(java.util.Objects::nonNull)
                        .map(IocProperties.ArtifactIdentity.Artifact.MatchKey::name)
                        .filter(DataframeImportPropertyMapper::hasText).forEach(schema.matchKeys::add);
            }
        }
        if (properties.export() != null && properties.export().profiles() != null) {
            for (IocProperties.Export.Profile profile : properties.export().profiles()) {
                if (profile == null || !hasText(profile.name()) || profile.artifacts() == null) {
                    continue;
                }
                for (String artifact : profile.artifacts()) {
                    if (hasText(artifact)) {
                        schemas.computeIfAbsent(artifact, ignored -> new MutableArtifactSchema())
                                .slotProfiles.add(profile.name());
                    }
                }
            }
        }
        Map<String, DataframeImportCatalogEnvironment.ArtifactSchema> artifacts = new LinkedHashMap<>();
        schemas.forEach((name, schema) -> artifacts.put(name, new DataframeImportCatalogEnvironment.ArtifactSchema(
                schema.columns, schema.recordKey, schema.matchKeys, schema.slotProfiles, schema.hasExternalId)));

        Set<String> endpoints = new LinkedHashSet<>();
        if (properties.sync() != null && properties.sync().endpoints() != null) {
            properties.sync().endpoints().stream().filter(java.util.Objects::nonNull)
                    .map(IocProperties.Sync.Endpoint::name).filter(DataframeImportPropertyMapper::hasText)
                    .forEach(endpoints::add);
        }
        return new DataframeImportCatalogEnvironment(artifacts, ConfigRegistryCatalog.transformKeys(), endpoints);
    }

    private static DataframeImportCatalogDraft.Source source(IocProperties.DataframeImport.SourceDefinition source) {
        return new DataframeImportCatalogDraft.Source(source.id(), source.transport(), source.location(),
                source.endpoint(), source.contracts(), source.authority());
    }

    private static DataframeImportCatalogDraft.AuthorityProfile authority(
            IocProperties.DataframeImport.AuthorityProfile authority) {
        return new DataframeImportCatalogDraft.AuthorityProfile(authority.id(), authority.artifacts(),
                authority.maximumMergePolicy(), authority.allowRelatedRouting(),
                authority.allowMachineOnlyFormulaPreserve());
    }

    private static DataframeImportCatalogDraft.Contract contract(IocProperties.DataframeImport.Contract contract) {
        return new DataframeImportCatalogDraft.Contract(contract.id(), contract.version(), contract.charset(),
                dialect(contract.dialect()), recognition(contract.recognition()), contract.mode(), contract.routing(),
                contract.rowFailurePolicy(), contract.duplicatePolicy(), contract.renewUnchanged(),
                contract.formulaPolicy(), contract.mergeDefault(),
                map(contract.artifacts(), DataframeImportPropertyMapper::artifact), requestedSlot(contract.requestedSlot()));
    }

    private static DataframeImportCatalogDraft.Dialect dialect(IocProperties.DataframeImport.Dialect dialect) {
        return dialect == null ? null : new DataframeImportCatalogDraft.Dialect(dialect.delimiter(), dialect.quote(),
                dialect.recordSeparator(), dialect.headerRequired(), dialect.nullLiterals());
    }

    private static DataframeImportCatalogDraft.Recognition recognition(
            IocProperties.DataframeImport.Recognition recognition) {
        return recognition == null ? null : new DataframeImportCatalogDraft.Recognition(
                recognition.requiredColumns(), recognition.optionalColumns(), recognition.ignoredColumns(),
                recognition.aliases());
    }

    private static DataframeImportCatalogDraft.Artifact artifact(IocProperties.DataframeImport.Artifact artifact) {
        return new DataframeImportCatalogDraft.Artifact(artifact.name(), artifact.role(), artifact.recordKey(), artifact.matchKeys(),
                artifact.mergeDefault(), map(artifact.columns(), DataframeImportPropertyMapper::column));
    }

    private static DataframeImportCatalogDraft.Column column(IocProperties.DataframeImport.Column column) {
        return new DataframeImportCatalogDraft.Column(
                column.target(), column.source(), column.transforms(), column.mergePolicy());
    }

    private static DataframeImportCatalogDraft.RequestedSlot requestedSlot(
            IocProperties.DataframeImport.RequestedSlot requestedSlot) {
        return requestedSlot == null ? null : new DataframeImportCatalogDraft.RequestedSlot(
                requestedSlot.sourceColumn(), requestedSlot.profile(), requestedSlot.existingRecordPolicy());
    }

    private static <S, T> List<T> map(List<S> source, Function<S, T> mapper) {
        if (source == null) {
            return null;
        }
        List<T> result = new ArrayList<>(source.size());
        for (S value : source) {
            result.add(value == null ? null : mapper.apply(value));
        }
        return result;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class MutableArtifactSchema {
        private final Set<String> columns = new LinkedHashSet<>();
        private String recordKey;
        private final Set<String> matchKeys = new LinkedHashSet<>();
        private final Set<String> slotProfiles = new LinkedHashSet<>();
        private boolean hasExternalId;
    }
}
