package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Complete isolated candidate composition; no configuration switch or migration is implied. */
record JdbcStagedCanonicalConfiguration(DataSource dataSource, List<DataframeArtifactSchema> schemas,
                                        List<ArtifactIdAllocatorDefinition> publicIds,
                                        List<ArtifactIdentityDefinition> identities,
                                        JdbcLifecycleClock clock, Clock allocatorClock,
                                        FixedRecordValidityPolicy validity, JdbcWriterAdmission admission,
                                        JdbcDocumentPreparationWorkspaceFactory workspaces, Path importWorkspaceRoot,
                                        Map<String, ArtifactWritePolicy> policies, Duration importReceiptRetention) {
    JdbcStagedCanonicalConfiguration {
        schemas = List.copyOf(schemas); publicIds = List.copyOf(publicIds); identities = List.copyOf(identities);
        policies = Map.copyOf(policies);
        java.util.Objects.requireNonNull(dataSource, "dataSource"); java.util.Objects.requireNonNull(clock, "clock");
        java.util.Objects.requireNonNull(allocatorClock, "allocatorClock"); java.util.Objects.requireNonNull(validity, "validity");
        java.util.Objects.requireNonNull(admission, "admission"); java.util.Objects.requireNonNull(importWorkspaceRoot, "importWorkspaceRoot");
        workspaces.requireCanonicalCapacity();
        if (schemas.isEmpty() || schemas.size() > 128 || importReceiptRetention.isZero() || importReceiptRetention.isNegative()) {
            throw new IllegalArgumentException("Invalid staged canonical catalog or retention");
        }
        validateCatalog(schemas, publicIds, identities, policies);
    }

    private static void validateCatalog(List<DataframeArtifactSchema> schemas, List<ArtifactIdAllocatorDefinition> publicIds,
                                        List<ArtifactIdentityDefinition> identities, Map<String, ArtifactWritePolicy> policies) {
        var identityMap = identities.stream().collect(java.util.stream.Collectors.toMap(ArtifactIdentityDefinition::artifactName,
                java.util.function.Function.identity()));
        var idMap = publicIds.stream().collect(java.util.stream.Collectors.toMap(ArtifactIdAllocatorDefinition::artifact,
                java.util.function.Function.identity()));
        var names = schemas.stream().map(DataframeArtifactSchema::artifactName).collect(java.util.stream.Collectors.toSet());
        if (names.size() != schemas.size() || !identityMap.keySet().equals(names) || !names.containsAll(policies.keySet())) {
            throw new IllegalArgumentException("Staged schema, identity and policy catalogs disagree");
        }
        var withId = new java.util.HashSet<String>();
        for (var schema : schemas) {
            if (validateSchema(schema, identityMap.get(schema.artifactName()), idMap.get(schema.artifactName()))) {
                withId.add(schema.artifactName());
            }
        }
        if (!idMap.keySet().equals(withId)) { throw new IllegalArgumentException("Staged public ID catalog differs from schema"); }
    }

    private static boolean validateSchema(DataframeArtifactSchema schema, ArtifactIdentityDefinition identity,
                                           ArtifactIdAllocatorDefinition allocator) {
        var columns = schema.columns().stream().map(DataframeColumn::name).collect(java.util.stream.Collectors.toSet());
        if (schema.artifactName().startsWith("publication_") || columns.isEmpty() || columns.size() != schema.columns().size()) {
            throw new IllegalArgumentException("Invalid staged native schema");
        }
        var keys = new java.util.ArrayList<>(identity.matchKeys()); keys.add(identity.recordKey());
        if (keys.stream().anyMatch(key -> !columns.containsAll(key.columns()))) {
            throw new IllegalArgumentException("Staged identity references unknown public fields");
        }
        boolean withId = columns.contains("id");
        if (withId && (allocator == null || allocator.identityEpoch() != identity.epoch())) {
            throw new IllegalArgumentException("Staged public ID catalog disagrees with identity epoch");
        }
        return withId;
    }

    String fingerprint() {
        var orderedPolicies = new java.util.TreeMap<String, String>();
        policies.forEach((artifact, policy) -> orderedPolicies.put(artifact,
                policy.duplicateSelection() + ":" + policy.selectionColumn() + ":" + new java.util.TreeMap<>(policy.fields())));
        return ArtifactIdentityDefinition.sha256("staged-native-v1/schema:12\n" + schemas + "\n" + publicIds
                + "\n" + identities + "\n" + validity.ttl() + "\n" + orderedPolicies + "\n" + importReceiptRetention);
    }
}
