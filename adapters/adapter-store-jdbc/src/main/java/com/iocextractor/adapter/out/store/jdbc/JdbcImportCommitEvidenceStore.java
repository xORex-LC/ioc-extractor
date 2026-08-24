package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportCommitEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Indexed bounded reader and retention adapter for dataframe import receipts. */
public final class JdbcImportCommitEvidenceStore implements ImportCommitEvidenceStore {

    private static final int DEFAULT_MAXIMUM_ISSUES = 10_000;

    private final JdbcClient jdbc;
    private final int maximumIssues;

    /** Creates a receipt store with a bounded report corpus. */
    public JdbcImportCommitEvidenceStore(DataSource dataSource) {
        this(dataSource, DEFAULT_MAXIMUM_ISSUES);
    }

    /** Creates a receipt store with an explicit maximum number of row issues. */
    public JdbcImportCommitEvidenceStore(DataSource dataSource, int maximumIssues) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        if (maximumIssues < 1) {
            throw new IllegalArgumentException("Maximum import receipt issues must be positive");
        }
        this.maximumIssues = maximumIssues;
    }

    @Override
    public Optional<ImportCommitEvidence> find(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        try {
            Optional<Receipt> receipt = jdbc.sql("""
                            SELECT accepted_rows, rejected_rows, public_mutations
                            FROM import_commit WHERE delivery_id = :delivery_id
                            """)
                    .param("delivery_id", deliveryId.value())
                    .query((row, ignored) -> new Receipt(
                            row.getLong("accepted_rows"),
                            row.getLong("rejected_rows"),
                            row.getLong("public_mutations")))
                    .optional();
            return receipt.map(value -> new ImportCommitEvidence(
                    deliveryId, value.acceptedRows(), value.rejectedRows(), value.publicMutations(),
                    affectedArtifacts(deliveryId), issues(deliveryId)));
        } catch (RuntimeException failure) {
            throw new IocExtractorException("Cannot read canonical import receipt evidence", failure);
        }
    }

    @Override
    public void purge(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        try {
            jdbc.sql("DELETE FROM import_commit WHERE delivery_id = :delivery_id")
                    .param("delivery_id", deliveryId.value())
                    .update();
        } catch (RuntimeException failure) {
            throw new IocExtractorException("Cannot purge canonical import receipt evidence", failure);
        }
    }

    private Set<String> affectedArtifacts(ImportDeliveryId deliveryId) {
        List<String> artifacts = jdbc.sql("""
                        SELECT artifact FROM import_commit_artifact
                        WHERE delivery_id = :delivery_id AND public_mutation = 1
                        ORDER BY artifact
                        """)
                .param("delivery_id", deliveryId.value())
                .query(String.class)
                .list();
        return Set.copyOf(new LinkedHashSet<>(artifacts));
    }

    private List<ImportRowIssue> issues(ImportDeliveryId deliveryId) {
        return jdbc.sql("""
                        SELECT source_row_number, artifact, diagnostic_code
                        FROM import_row_rejection
                        WHERE delivery_id = :delivery_id
                        ORDER BY rejection_ordinal
                        LIMIT :maximum_issues
                        """)
                .param("delivery_id", deliveryId.value())
                .param("maximum_issues", maximumIssues)
                .query((row, ignored) -> new ImportRowIssue(
                        row.getLong("source_row_number"),
                        row.getString("artifact"),
                        row.getString("diagnostic_code")))
                .list();
    }

    private record Receipt(long acceptedRows, long rejectedRows, long publicMutations) {
    }
}
