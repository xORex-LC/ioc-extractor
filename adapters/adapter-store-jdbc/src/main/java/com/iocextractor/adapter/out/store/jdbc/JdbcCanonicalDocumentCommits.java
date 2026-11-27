package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.DocumentArtifactCommit;
import com.iocextractor.application.artifact.DocumentWriteIdentity;
import com.iocextractor.application.port.out.artifact.CanonicalDocumentCommits;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/** Reads document recovery proof and the registration authority in one SQLite snapshot. */
public final class JdbcCanonicalDocumentCommits implements CanonicalDocumentCommits {

    private final DataSource dataSource;
    private final JdbcOrderedFieldStore registrations = new JdbcOrderedFieldStore();

    public JdbcCanonicalDocumentCommits(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Map<String, DocumentArtifactCommit> find(DocumentWriteIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        try (var connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            try {
                registrations.validateRegistration(connection, identity.registration());
                var commits = JdbcDocumentCommitMarkers.find(connection, identity);
                connection.commit();
                return commits;
            } catch (SQLException | RuntimeException | Error caught) {
                failure = caught;
                JdbcLifecycleTransactions.rollback(connection, caught);
                throw caught;
            } finally {
                JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to read canonical document evidence", failure);
        }
    }
}
