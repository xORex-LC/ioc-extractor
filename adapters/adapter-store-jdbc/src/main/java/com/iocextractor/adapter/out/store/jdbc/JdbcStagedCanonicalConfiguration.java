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
    }

    String fingerprint() {
        var orderedPolicies = new java.util.TreeMap<String, String>();
        policies.forEach((artifact, policy) -> orderedPolicies.put(artifact,
                policy.duplicateSelection() + ":" + policy.selectionColumn() + ":" + new java.util.TreeMap<>(policy.fields())));
        return ArtifactIdentityDefinition.sha256("staged-native-v1/schema:12\n" + schemas + "\n" + publicIds
                + "\n" + identities + "\n" + validity.ttl() + "\n" + orderedPolicies + "\n" + importReceiptRetention);
    }
}
