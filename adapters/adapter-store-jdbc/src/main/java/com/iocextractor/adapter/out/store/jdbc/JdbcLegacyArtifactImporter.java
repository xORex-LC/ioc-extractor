package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * One-shot legacy import for generated CSV artifacts. Rows are read through a
 * source {@link CanonicalArtifactRepository} (the CSV adapter owns the CSV
 * dialect) and written through {@link JdbcCanonicalArtifactRepository} so
 * row-key and keep-first semantics stay in one place.
 *
 * <p>The importer preserves explicit legacy ids and then raises SQLite
 * identity sequences to the maximum imported id. Import summaries count rows
 * actually inserted into canonical storage rather than duplicate source rows.
 */
public final class JdbcLegacyArtifactImporter {

    private final DataSource dataSource;
    private final JdbcCanonicalArtifactRepository target;
    private final CanonicalArtifactRepository source;
    private final List<DataframeArtifactSchema> schemas;

    public JdbcLegacyArtifactImporter(DataSource dataSource,
                                      JdbcCanonicalArtifactRepository target,
                                      CanonicalArtifactRepository source,
                                      List<DataframeArtifactSchema> schemas) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.target = Objects.requireNonNull(target, "target");
        this.source = Objects.requireNonNull(source, "source");
        this.schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas"));
    }

    public ImportSummary importAll() {
        int artifacts = 0;
        int rows = 0;
        for (DataframeArtifactSchema schema : schemas) {
            CanonicalArtifact artifact = source.load(schema.artifactName());
            if (artifact.rows().isEmpty()) {
                continue;
            }
            int inserted = target.write(schema.artifactName(), artifact).inserted();
            if (inserted > 0) {
                artifacts++;
                rows += inserted;
            }
        }
        long sequenceFloor = maxIdInImportedArtifacts();
        if (sequenceFloor > 0) {
            bumpSequences(sequenceFloor);
        }
        return new ImportSummary(artifacts, rows, sequenceFloor);
    }

    private long maxIdInImportedArtifacts() {
        JdbcLookupRepository lookup = new JdbcLookupRepository(dataSource);
        return schemas.stream()
                .mapToLong(schema -> lookup.maxId(schema.artifactName()))
                .max()
                .orElse(0L);
    }

    /**
     * Lifts every per-artifact identity sequence to the imported max id. Gaps
     * below it are acceptable under the ascending/unique (not gapless) id contract.
     */
    private void bumpSequences(long sequenceFloor) {
        try (Connection connection = dataSource.getConnection();
             var update = connection.prepareStatement("""
                     UPDATE sqlite_sequence
                     SET seq = max(seq, ?)
                     WHERE name = ?
                     """);
             var insert = connection.prepareStatement("""
                     INSERT INTO sqlite_sequence(name, seq)
                     SELECT ?, ?
                     WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = ?)
                     """)) {
            for (DataframeArtifactSchema schema : schemas) {
                update.setLong(1, sequenceFloor);
                update.setString(2, schema.artifactName());
                update.addBatch();

                insert.setString(1, schema.artifactName());
                insert.setLong(2, sequenceFloor);
                insert.setString(3, schema.artifactName());
                insert.addBatch();
            }
            update.executeBatch();
            insert.executeBatch();
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to bump dataframe identity sequences", e);
        }
    }

    public record ImportSummary(int artifacts, int rows, long sequenceFloor) {
    }
}
