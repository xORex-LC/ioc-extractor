package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.policy.FieldUpdateDecision;
import com.iocextractor.application.artifact.policy.FieldValueOrigin;
import com.iocextractor.application.artifact.policy.LatestRegisteredValuePolicy;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Connection-scoped persistence for ordered mutable-field values and provenance. */
final class JdbcOrderedFieldStore {

    private final LatestRegisteredValuePolicy policy = new LatestRegisteredValuePolicy();

    void validateRegistration(Connection connection, RegisteredObservation registration)
            throws SQLException {
        Objects.requireNonNull(registration, "registration");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.admission_order, r.origin_kind, c.namespace_id
                FROM registered_observation r
                CROSS JOIN observation_order_control c
                WHERE r.occurrence_id = ? AND c.singleton_id = 1
                """)) {
            statement.setString(1, registration.observationId().value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong("admission_order") != registration.admissionOrder().value()
                        || !result.getString("origin_kind").equals(registration.origin().name())
                        || !result.getString("namespace_id").equals(registration.namespaceId())) {
                    throw new IocExtractorException("Registered observation does not match dataframe authority");
                }
            }
        }
    }

    Resolution resolveLifecycle(Connection connection,
                                String artifact,
                                long lifecycleId,
                                ArtifactRow current,
                                PreparedArtifactRow incoming,
                                RegisteredObservation registration) throws SQLException {
        return resolve(connection, Scope.lifecycle(artifact, lifecycleId), current, incoming, registration);
    }

    Resolution resolveCompatibility(Connection connection,
                                    String artifact,
                                    String rowKey,
                                    long identityEpoch,
                                    ArtifactRow current,
                                    PreparedArtifactRow incoming,
                                    RegisteredObservation registration) throws SQLException {
        return resolve(connection, Scope.compatibility(artifact, rowKey, identityEpoch),
                current, incoming, registration);
    }

    void initializeLifecycle(Connection connection,
                             String artifact,
                             long lifecycleId,
                             PreparedArtifactRow incoming,
                             RegisteredObservation registration) throws SQLException {
        initialize(connection, Scope.lifecycle(artifact, lifecycleId), incoming, registration);
    }

    void initializeCompatibility(Connection connection,
                                 String artifact,
                                 String rowKey,
                                 long identityEpoch,
                                 PreparedArtifactRow incoming,
                                 RegisteredObservation registration) throws SQLException {
        initialize(connection, Scope.compatibility(artifact, rowKey, identityEpoch), incoming, registration);
    }

    private Resolution resolve(Connection connection,
                               Scope scope,
                               ArtifactRow current,
                               PreparedArtifactRow incoming,
                               RegisteredObservation registration) throws SQLException {
        ArtifactRow finalRow = current;
        Set<String> changed = new LinkedHashSet<>();
        boolean metadataChanged = false;
        for (var field : incoming.orderedFieldPositions().entrySet()) {
            String name = field.getKey();
            String incomingValue = incoming.template().value(name);
            FieldValueOrigin incomingOrigin = origin(registration, field.getValue());
            FieldValueOrigin currentOrigin = load(connection, scope, name);
            FieldUpdateDecision decision = policy.decide(
                    current.value(name), currentOrigin, incomingValue, incomingOrigin);
            if (decision == FieldUpdateDecision.CHANGE_PUBLIC_VALUE) {
                finalRow = finalRow.withValue(name, incomingValue);
                changed.add(name);
                upsert(connection, scope, name, incomingOrigin);
                metadataChanged = true;
            } else if (decision == FieldUpdateDecision.ADVANCE_ORIGIN_ONLY) {
                upsert(connection, scope, name, incomingOrigin);
                metadataChanged = true;
            }
        }
        return new Resolution(finalRow, changed, metadataChanged);
    }

    private void initialize(Connection connection,
                            Scope scope,
                            PreparedArtifactRow incoming,
                            RegisteredObservation registration) throws SQLException {
        if (incoming.orderedFieldPositions().isEmpty()) {
            return;
        }
        Objects.requireNonNull(registration, "registration");
        for (var field : incoming.orderedFieldPositions().entrySet()) {
            String value = incoming.template().value(field.getKey());
            if (value != null && !value.isBlank()) {
                upsert(connection, scope, field.getKey(), origin(registration, field.getValue()));
            }
        }
    }

    private FieldValueOrigin origin(RegisteredObservation registration, OccurrencePosition position) {
        if (registration == null) {
            throw new IllegalArgumentException("Ordered field mutation requires a registered observation");
        }
        return new FieldValueOrigin(
                registration.admissionOrder(), position, registration.observationId());
    }

    private FieldValueOrigin load(Connection connection, Scope scope, String field) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(scope.selectSql())) {
            int index = scope.bindIdentity(statement);
            statement.setString(index, field);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new FieldValueOrigin(
                        new ObservationOrder(result.getLong("admission_order")),
                        new OccurrencePosition(result.getLong("occurrence_position")),
                        new ObservationId(result.getString("occurrence_id")));
            }
        }
    }

    private void upsert(Connection connection,
                        Scope scope,
                        String field,
                        FieldValueOrigin origin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(scope.upsertSql())) {
            int index = scope.bindIdentity(statement);
            statement.setString(index++, field);
            statement.setLong(index++, origin.admissionOrder().value());
            statement.setLong(index++, origin.occurrencePosition().value());
            statement.setString(index, origin.observationId().value());
            statement.executeUpdate();
        }
    }

    record Resolution(ArtifactRow finalRow, Set<String> publicChangedFields, boolean metadataChanged) {
        Resolution {
            Objects.requireNonNull(finalRow, "finalRow");
            publicChangedFields = Collections.unmodifiableSet(new LinkedHashSet<>(
                    Objects.requireNonNull(publicChangedFields, "publicChangedFields")));
        }

        boolean publicChanged() {
            return !publicChangedFields.isEmpty();
        }
    }

    private record Scope(String artifact, Long lifecycleId, String rowKey, Long identityEpoch) {

        static Scope lifecycle(String artifact, long lifecycleId) {
            return new Scope(artifact, lifecycleId, null, null);
        }

        static Scope compatibility(String artifact, String rowKey, long identityEpoch) {
            return new Scope(artifact, null, rowKey, identityEpoch);
        }

        private boolean lifecycle() {
            return lifecycleId != null;
        }

        private String selectSql() {
            return lifecycle() ? """
                    SELECT admission_order, occurrence_position, occurrence_id
                    FROM canonical_lifecycle_field_origin
                    WHERE artifact = ? AND lifecycle_id = ? AND field_name = ?
                    """ : """
                    SELECT admission_order, occurrence_position, occurrence_id
                    FROM canonical_compat_field_origin
                    WHERE artifact = ? AND row_key = ? AND identity_epoch = ? AND field_name = ?
                    """;
        }

        private String upsertSql() {
            return lifecycle() ? """
                    INSERT INTO canonical_lifecycle_field_origin(
                        artifact, lifecycle_id, field_name, admission_order,
                        occurrence_position, occurrence_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(artifact, lifecycle_id, field_name) DO UPDATE SET
                        admission_order = excluded.admission_order,
                        occurrence_position = excluded.occurrence_position,
                        occurrence_id = excluded.occurrence_id
                    """ : """
                    INSERT INTO canonical_compat_field_origin(
                        artifact, row_key, identity_epoch, field_name, admission_order,
                        occurrence_position, occurrence_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(artifact, row_key, identity_epoch, field_name) DO UPDATE SET
                        admission_order = excluded.admission_order,
                        occurrence_position = excluded.occurrence_position,
                        occurrence_id = excluded.occurrence_id
                    """;
        }

        private int bindIdentity(PreparedStatement statement) throws SQLException {
            statement.setString(1, artifact);
            if (lifecycle()) {
                statement.setLong(2, lifecycleId);
                return 3;
            }
            statement.setString(2, rowKey);
            statement.setLong(3, identityEpoch);
            return 4;
        }
    }
}
