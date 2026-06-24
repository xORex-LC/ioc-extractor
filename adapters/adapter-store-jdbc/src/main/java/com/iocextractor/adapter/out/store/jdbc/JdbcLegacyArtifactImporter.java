package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-shot legacy import for generated CSV artifacts and the old stable-id
 * sidecar. The importer delegates row writes to {@link JdbcCanonicalArtifactRepository}
 * so row-key and keep-first semantics stay in one place.
 */
public final class JdbcLegacyArtifactImporter {

    private final DataSource dataSource;
    private final JdbcCanonicalArtifactRepository repository;
    private final List<DataframeArtifactSchema> schemas;
    private final Map<String, Path> artifactPaths;
    private final Path idIndexPath;
    private final Charset charset;

    public JdbcLegacyArtifactImporter(DataSource dataSource,
                                      JdbcCanonicalArtifactRepository repository,
                                      List<DataframeArtifactSchema> schemas,
                                      Map<String, Path> artifactPaths,
                                      Path idIndexPath) {
        this(dataSource, repository, schemas, artifactPaths, idIndexPath, StandardCharsets.UTF_8);
    }

    public JdbcLegacyArtifactImporter(DataSource dataSource,
                                      JdbcCanonicalArtifactRepository repository,
                                      List<DataframeArtifactSchema> schemas,
                                      Map<String, Path> artifactPaths,
                                      Path idIndexPath,
                                      Charset charset) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas"));
        this.artifactPaths = Map.copyOf(Objects.requireNonNull(artifactPaths, "artifactPaths"));
        this.idIndexPath = idIndexPath;
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    public ImportSummary importAll() {
        int artifacts = 0;
        int rows = 0;
        for (DataframeArtifactSchema schema : schemas) {
            Path path = artifactPaths.get(schema.artifactName());
            if (path == null || !Files.exists(path)) {
                continue;
            }
            List<ArtifactRow> importedRows = readArtifact(path);
            repository.write(schema.artifactName(), new CanonicalArtifact(
                    schema.artifactName(), header(schema), importedRows));
            artifacts++;
            rows += importedRows.size();
        }
        long sequenceFloor = Math.max(maxIdInImportedArtifacts(), maxIdInSidecar());
        if (sequenceFloor > 0) {
            bumpSequences(sequenceFloor);
        }
        return new ImportSummary(artifacts, rows, sequenceFloor);
    }

    private List<ArtifactRow> readArtifact(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, charset);
            if (lines.isEmpty()) {
                return List.of();
            }
            List<String> headers = parseLine(lines.getFirst());
            List<ArtifactRow> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                List<String> values = parseLine(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    String value = j < values.size() ? values.get(j) : null;
                    row.put(headers.get(j), "NULL".equals(value) ? null : value);
                }
                rows.add(ArtifactRow.ordered(row));
            }
            return rows;
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read legacy artifact CSV: " + path, e);
        }
    }

    private long maxIdInImportedArtifacts() {
        return schemas.stream()
                .mapToLong(schema -> new JdbcLookupRepository(dataSource).maxId(schema.artifactName()))
                .max()
                .orElse(0L);
    }

    private long maxIdInSidecar() {
        if (idIndexPath == null || !Files.exists(idIndexPath)) {
            return 0L;
        }
        try {
            List<String> lines = Files.readAllLines(idIndexPath, charset);
            if (lines.isEmpty()) {
                return 0L;
            }
            List<String> headers = parseLine(lines.getFirst());
            int idIndex = headers.indexOf("id");
            if (idIndex < 0) {
                return 0L;
            }
            long max = 0L;
            for (int i = 1; i < lines.size(); i++) {
                List<String> values = parseLine(lines.get(i));
                if (idIndex < values.size()) {
                    max = Math.max(max, parseLong(values.get(idIndex)));
                }
            }
            return max;
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read legacy id index: " + idIndexPath, e);
        }
    }

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

    private List<String> header(DataframeArtifactSchema schema) {
        return schema.columns().stream()
                .map(DataframeColumn::name)
                .toList();
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ';' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        values.add(value.toString());
        return values;
    }

    public record ImportSummary(int artifacts, int rows, long sequenceFloor) {
    }
}
