package com.iocextractor.consumer;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small repository-owned reference consumer for the public CSV and export-slice wires.
 *
 * <p>It intentionally depends on wire primitives only, not on the application's manifest
 * model, production codec or export catalog. That keeps the tests capable of detecting a
 * producer and decoder drifting together.</p>
 */
public final class ReferenceArtifactConsumer {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> ROOT_FIELDS = Set.of(
            "manifest_version", "slice_id", "run_id", "profile", "created_at",
            "output_mode", "plan_hash", "format", "artifacts");
    private static final Set<String> FORMAT_FIELDS = Set.of(
            "type", "charset", "delimiter", "quote", "null_literal");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "artifact", "file", "rows", "coverage", "identity_epoch",
            "identity_hash", "schema_hash", "sha256");
    private static final Set<String> COVERAGE_FIELDS = Set.of(
            "revision", "changed_at", "upper_id");

    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public CsvDocument readCsv(Path file, List<String> expectedHeader) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String content = strictUtf8(bytes);
        require(content.endsWith("\r\n"), "CSV must end with CRLF: " + file);
        String withoutCrLf = content.replace("\r\n", "");
        require(!withoutCrLf.contains("\r") && !withoutCrLf.contains("\n"),
                "CSV contains a non-CRLF record separator: " + file);

        CSVFormat format = CSVFormat.Builder.create()
                .setDelimiter(';')
                .setQuote('"')
                .setNullString("NULL")
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .build();
        try (CSVParser parser = format.parse(new StringReader(content))) {
            List<String> header = List.copyOf(parser.getHeaderNames());
            require(header.equals(expectedHeader),
                    "Unexpected CSV header in " + file + ": " + header);
            List<Map<String, String>> rows = new ArrayList<>();
            parser.forEach(record -> {
                require(record.size() == header.size(),
                        "Unexpected CSV row width in " + file + " at record " + record.getRecordNumber());
                Map<String, String> values = new LinkedHashMap<>();
                header.forEach(column -> values.put(column, record.get(column)));
                rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
            });
            return new CsvDocument(header, List.copyOf(rows), bytes);
        }
    }

    public SliceDocument readSlice(Path slice, Map<String, List<String>> expectedHeaders)
            throws IOException {
        Path manifestFile = slice.resolve("manifest.json");
        byte[] manifestBytes = Files.readAllBytes(manifestFile);
        JsonNode manifest = json.readTree(manifestBytes);
        requireObjectWithFields(manifest, ROOT_FIELDS, "manifest");
        require(manifest.path("manifest_version").isIntegralNumber()
                        && manifest.path("manifest_version").intValue() == 1,
                "Unsupported manifest_version");
        require("complete".equals(requiredText(manifest, "output_mode")),
                "Unsupported output_mode");
        Instant.parse(requiredText(manifest, "created_at"));

        JsonNode format = manifest.path("format");
        requireObjectWithFields(format, FORMAT_FIELDS, "format");
        require("csv".equals(requiredText(format, "type")), "Unsupported format.type");
        require("UTF-8".equals(requiredText(format, "charset")), "Unsupported format.charset");
        require(";".equals(requiredText(format, "delimiter")), "Unsupported format.delimiter");
        require("\"".equals(requiredText(format, "quote")), "Unsupported format.quote");
        require("NULL".equals(requiredText(format, "null_literal")), "Unsupported format.null_literal");
        requireHash(requiredText(manifest, "plan_hash"), "plan_hash");

        JsonNode artifacts = manifest.path("artifacts");
        require(artifacts.isArray() && !artifacts.isEmpty(), "Manifest artifacts must be a non-empty array");
        Map<String, CsvDocument> consumed = new LinkedHashMap<>();
        Set<String> expectedMembers = new HashSet<>(Set.of("manifest.json", "_SUCCESS"));
        for (JsonNode artifact : artifacts) {
            requireObjectWithFields(artifact, ARTIFACT_FIELDS, "artifact");
            JsonNode coverage = artifact.path("coverage");
            requireObjectWithFields(coverage, COVERAGE_FIELDS, "coverage");
            requireNonNegativeInteger(artifact, "rows");
            requireNonNegativeInteger(coverage, "revision");
            requireNonNegativeInteger(coverage, "upper_id");
            require(artifact.path("identity_epoch").isIntegralNumber()
                            && artifact.path("identity_epoch").intValue() > 0,
                    "identity_epoch must be a positive integer");
            JsonNode changedAt = coverage.path("changed_at");
            require(changedAt.isNull() || changedAt.isTextual(),
                    "coverage.changed_at must be text or null");
            if (changedAt.isTextual()) {
                Instant.parse(changedAt.textValue());
            }
            String artifactName = requiredText(artifact, "artifact");
            String fileName = requiredText(artifact, "file");
            require(Path.of(fileName).getNameCount() == 1, "Artifact file must be a plain file name");
            require(expectedHeaders.containsKey(artifactName),
                    "No accepted header for artifact: " + artifactName);
            require(expectedMembers.add(fileName), "Duplicate artifact file: " + fileName);
            Path artifactFile = slice.resolve(fileName);
            requireHash(requiredText(artifact, "identity_hash"), "identity_hash");
            requireHash(requiredText(artifact, "schema_hash"), "schema_hash");
            String expectedSha256 = requiredText(artifact, "sha256");
            requireHash(expectedSha256, "sha256");
            require(sha256(Files.readAllBytes(artifactFile)).equals(expectedSha256),
                    "Artifact checksum mismatch: " + fileName);
            CsvDocument csv = readCsv(artifactFile, expectedHeaders.get(artifactName));
            require(csv.rows().size() == artifact.path("rows").asLong(-1),
                    "Artifact row count mismatch: " + fileName);
            require(consumed.put(artifactName, csv) == null,
                    "Duplicate artifact name: " + artifactName);
        }

        Set<String> actualMembers = new HashSet<>();
        try (var members = Files.list(slice)) {
            members.forEach(path -> {
                require(Files.isRegularFile(path), "Slice member is not a regular file: " + path);
                actualMembers.add(path.getFileName().toString());
            });
        }
        require(actualMembers.equals(expectedMembers),
                "Unexpected slice membership: " + actualMembers);
        String marker = Files.readString(slice.resolve("_SUCCESS"), StandardCharsets.US_ASCII);
        require(marker.equals(sha256(manifestBytes) + "\n"), "_SUCCESS does not bind manifest.json");

        return new SliceDocument(
                requiredText(manifest, "slice_id"),
                requiredText(manifest, "profile"),
                Map.copyOf(consumed));
    }

    private String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private void requireObjectWithFields(JsonNode node, Set<String> expected, String owner) {
        require(node.isObject(), owner + " must be an object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "Unexpected " + owner + " fields: " + actual);
    }

    private String requiredText(JsonNode owner, String field) {
        JsonNode value = owner.path(field);
        require(value.isTextual() && !value.textValue().isBlank(), field + " must be non-blank text");
        return value.textValue();
    }

    private void requireNonNegativeInteger(JsonNode owner, String field) {
        JsonNode value = owner.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0,
                field + " must be a non-negative integer");
    }

    private void requireHash(String value, String field) {
        require(SHA256.matcher(value).matches(), field + " must be a lowercase SHA-256 value");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public record CsvDocument(
            List<String> header,
            List<Map<String, String>> rows,
            byte[] bytes) {

        public CsvDocument {
            header = List.copyOf(header);
            rows = List.copyOf(rows);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record SliceDocument(
            String sliceId,
            String profile,
            Map<String, CsvDocument> artifacts) {

        public SliceDocument {
            artifacts = Map.copyOf(artifacts);
        }
    }
}
