package com.iocextractor.adapter.out.manifest.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.common.IocExtractorException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic Jackson implementation of the version-1 slice manifest wire format.
 *
 * <p>Jackson annotations are isolated on adapter-owned document records. The
 * application model remains serialization-neutral, while explicit property
 * orders make the exact UTF-8 bytes stable and suitable for content hashing.
 */
public final class JacksonSliceManifestCodec implements SliceManifestCodec {

    public static final int SUPPORTED_VERSION = 1;

    private final ObjectReader reader;
    private final ObjectWriter writer;

    public JacksonSliceManifestCodec() {
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.reader = mapper.readerFor(ManifestDocument.class);
        this.writer = mapper.writerFor(ManifestDocument.class);
    }

    @Override
    public byte[] encode(SliceManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        requireSupportedVersion(manifest.manifestVersion());
        try {
            return writer.writeValueAsBytes(toDocument(manifest));
        } catch (JsonProcessingException e) {
            throw new IocExtractorException("Failed to encode slice manifest", e);
        }
    }

    @Override
    public SliceManifest decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            ManifestDocument document = reader.readValue(bytes);
            requireSupportedVersion(document.manifestVersion());
            return toModel(document);
        } catch (IocExtractorException e) {
            throw e;
        } catch (Exception e) {
            throw new IocExtractorException("Failed to decode slice manifest", e);
        }
    }

    private ManifestDocument toDocument(SliceManifest manifest) {
        return new ManifestDocument(
                manifest.manifestVersion(),
                manifest.sliceId(),
                manifest.runId(),
                manifest.profile(),
                manifest.createdAt().toString(),
                manifest.outputMode().name().toLowerCase(Locale.ROOT),
                manifest.planHash(),
                new FormatDocument(
                        manifest.format().type(),
                        manifest.format().charset(),
                        manifest.format().delimiter(),
                        manifest.format().quote(),
                        manifest.format().nullLiteral()),
                manifest.artifacts().stream().map(this::toDocument).toList());
    }

    private ArtifactDocument toDocument(SliceArtifactManifest artifact) {
        ArtifactCoverage coverage = artifact.coverage();
        return new ArtifactDocument(
                artifact.artifactName(),
                artifact.fileName(),
                artifact.rows(),
                new CoverageDocument(
                        coverage.revision(),
                        coverage.changedAt() == null ? null : coverage.changedAt().toString(),
                        coverage.upperId()),
                artifact.identityEpoch(),
                artifact.identityHash(),
                artifact.schemaHash(),
                artifact.sha256());
    }

    private SliceManifest toModel(ManifestDocument document) {
        FormatDocument format = Objects.requireNonNull(document.format(), "format");
        List<ArtifactDocument> artifacts = List.copyOf(
                Objects.requireNonNull(document.artifacts(), "artifacts"));
        return new SliceManifest(
                document.manifestVersion(),
                document.sliceId(),
                document.runId(),
                document.profile(),
                Instant.parse(document.createdAt()),
                ExportMode.valueOf(document.outputMode().toUpperCase(Locale.ROOT)),
                document.planHash(),
                new ExportFormat(
                        format.type(), format.charset(), format.delimiter(),
                        format.quote(), format.nullLiteral()),
                artifacts.stream().map(this::toModel).toList());
    }

    private SliceArtifactManifest toModel(ArtifactDocument artifact) {
        CoverageDocument coverage = Objects.requireNonNull(artifact.coverage(), "coverage");
        return new SliceArtifactManifest(
                artifact.artifact(),
                artifact.file(),
                artifact.rows(),
                new ArtifactCoverage(
                        coverage.revision(),
                        coverage.changedAt() == null ? null : Instant.parse(coverage.changedAt()),
                        coverage.upperId()),
                artifact.identityEpoch(),
                artifact.identityHash(),
                artifact.schemaHash(),
                artifact.sha256());
    }

    private void requireSupportedVersion(int version) {
        if (version != SUPPORTED_VERSION) {
            throw new IocExtractorException("Unsupported slice manifest version: " + version);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({
            "manifest_version", "slice_id", "run_id", "profile", "created_at",
            "output_mode", "plan_hash", "format", "artifacts"
    })
    private record ManifestDocument(
            @JsonProperty("manifest_version") int manifestVersion,
            @JsonProperty("slice_id") String sliceId,
            @JsonProperty("run_id") String runId,
            @JsonProperty("profile") String profile,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("output_mode") String outputMode,
            @JsonProperty("plan_hash") String planHash,
            @JsonProperty("format") FormatDocument format,
            @JsonProperty("artifacts") List<ArtifactDocument> artifacts) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"type", "charset", "delimiter", "quote", "null_literal"})
    private record FormatDocument(
            @JsonProperty("type") String type,
            @JsonProperty("charset") String charset,
            @JsonProperty("delimiter") String delimiter,
            @JsonProperty("quote") String quote,
            @JsonProperty("null_literal") String nullLiteral) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({
            "artifact", "file", "rows", "coverage", "identity_epoch",
            "identity_hash", "schema_hash", "sha256"
    })
    private record ArtifactDocument(
            @JsonProperty("artifact") String artifact,
            @JsonProperty("file") String file,
            @JsonProperty("rows") long rows,
            @JsonProperty("coverage") CoverageDocument coverage,
            @JsonProperty("identity_epoch") int identityEpoch,
            @JsonProperty("identity_hash") String identityHash,
            @JsonProperty("schema_hash") String schemaHash,
            @JsonProperty("sha256") String sha256) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"revision", "changed_at", "upper_id"})
    private record CoverageDocument(
            @JsonProperty("revision") long revision,
            @JsonProperty("changed_at") String changedAt,
            @JsonProperty("upper_id") long upperId) {
    }
}
