package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.export.ArtifactSchemaFingerprint;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves and validates configuration-shaped export profiles into application plans.
 *
 * <p>This bootstrap component is the only bridge from {@link IocProperties} to the
 * storage-neutral export model. Validation performs no filesystem or database IO.
 */
public final class ExportPlanCatalog {

    private static final Set<String> RESERVED_SLICE_FILES = Set.of("manifest.json", "_SUCCESS");

    private final List<ExportPlan> plans;

    /**
     * Resolves every configured profile and fails before infrastructure graph activation.
     *
     * @param properties complete bound IOC configuration
     * @param diagnosticSink destination for unsupported-mode diagnostics
     * @param diagnosticFactory diagnostic timestamp factory
     */
    public ExportPlanCatalog(IocProperties properties,
                             DiagnosticSink diagnosticSink,
                             DiagnosticFactory diagnosticFactory) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.plans = resolve(properties, diagnosticSink, diagnosticFactory);
    }

    /** Returns immutable plans in configured profile order. */
    public List<ExportPlan> plans() {
        return plans;
    }

    /**
     * Verifies that a configured plan exists without activating export infrastructure.
     *
     * @param profile requested profile name
     * @throws IllegalArgumentException when the profile is not configured
     */
    public void requireProfile(String profile) {
        if (plans.stream().noneMatch(plan -> plan.profile().name().equals(profile))) {
            throw new IllegalArgumentException("Unknown export profile: " + profile);
        }
    }

    private List<ExportPlan> resolve(IocProperties properties,
                                     DiagnosticSink diagnosticSink,
                                     DiagnosticFactory diagnosticFactory) {
        Map<String, IocProperties.Sink.Artifact> sinkArtifacts = sinkArtifacts(properties);
        Map<String, ArtifactIdentityDefinition> identities = identities(properties);
        IocProperties.Sink.Csv csv = properties.sink().csv();
        ExportFormat format = format(csv);
        List<ExportPlan> result = new ArrayList<>();
        HashSet<String> profileNames = new HashSet<>();
        for (IocProperties.Export.Profile configured : properties.export().profiles()) {
            if (!profileNames.add(configured.name())) {
                throw new IllegalArgumentException("Duplicate export profile: " + configured.name());
            }
            ExportMode mode = outputMode(configured, diagnosticSink, diagnosticFactory);
            List<ExportArtifactSpec> artifacts = configured.artifacts().stream()
                    .map(name -> artifact(configured.name(), name, sinkArtifacts, identities))
                    .toList();
            List<String> fileNames = artifacts.stream().map(artifact -> artifact.fileName()).toList();
            if (fileNames.stream().distinct().count() != fileNames.size()) {
                throw new IllegalArgumentException("Export profile has duplicate output file names: "
                        + configured.name());
            }
            if (fileNames.stream().anyMatch(RESERVED_SLICE_FILES::contains)) {
                throw new IllegalArgumentException("Export profile uses a reserved slice file name: "
                        + configured.name());
            }
            result.add(new ExportPlan(1,
                    new ExportProfile(configured.name(), mode, configured.artifacts()),
                    format, artifacts));
        }
        return List.copyOf(result);
    }

    private Map<String, IocProperties.Sink.Artifact> sinkArtifacts(IocProperties properties) {
        Map<String, IocProperties.Sink.Artifact> result = new LinkedHashMap<>();
        for (IocProperties.Sink.Artifact artifact : properties.sink().artifacts()) {
            if (result.put(artifact.name(), artifact) != null) {
                throw new IllegalArgumentException("Duplicate sink artifact: " + artifact.name());
            }
        }
        return result;
    }

    private Map<String, ArtifactIdentityDefinition> identities(IocProperties properties) {
        Map<String, ArtifactIdentityDefinition> result = new LinkedHashMap<>();
        for (IocProperties.ArtifactIdentity.Artifact configured : properties.artifactIdentity().artifacts()) {
            ArtifactIdentityDefinition definition = new ArtifactIdentityDefinition(
                    configured.name(), configured.keyColumns(),
                    "first-non-empty".equalsIgnoreCase(Objects.toString(configured.keyMode(), "")),
                    configured.epoch() == null ? 1 : configured.epoch());
            if (result.put(configured.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate artifact identity: " + configured.name());
            }
        }
        return result;
    }

    private ExportArtifactSpec artifact(String profile,
                                        String name,
                                        Map<String, IocProperties.Sink.Artifact> sinkArtifacts,
                                        Map<String, ArtifactIdentityDefinition> identities) {
        IocProperties.Sink.Artifact sink = sinkArtifacts.get(name);
        if (sink == null || !sink.enabled()) {
            throw new IllegalArgumentException("Export profile " + profile
                    + " references unknown or disabled sink artifact: " + name);
        }
        ArtifactIdentityDefinition identity = identities.get(name);
        if (identity == null) {
            throw new IllegalArgumentException("Export artifact has no identity definition: " + name);
        }
        List<String> columns = sink.columns().stream().map(column -> column.name()).toList();
        List<String> types = sink.columns().stream().map(column -> column.type()).toList();
        Path file = Path.of(sink.path()).getFileName();
        if (file == null) {
            throw new IllegalArgumentException("Sink artifact path has no file name: " + sink.path());
        }
        return new ExportArtifactSpec(name, file.toString(), columns, identity.epoch(),
                identity.identityHash(), ArtifactSchemaFingerprint.sha256(columns, types),
                mappingHash(sink));
    }

    private ExportMode outputMode(IocProperties.Export.Profile profile,
                                  DiagnosticSink diagnosticSink,
                                  DiagnosticFactory diagnosticFactory) {
        ExportMode mode;
        try {
            mode = ExportMode.valueOf(profile.outputMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw unsupported(profile, profile.outputMode(), diagnosticSink, diagnosticFactory, failure);
        }
        if (mode != ExportMode.COMPLETE) {
            throw unsupported(profile, mode.name(), diagnosticSink, diagnosticFactory, null);
        }
        return mode;
    }

    private DiagnosticException unsupported(IocProperties.Export.Profile profile,
                                            String mode,
                                            DiagnosticSink diagnosticSink,
                                            DiagnosticFactory diagnosticFactory,
                                            Throwable cause) {
        var builder = diagnosticFactory.create(ExportDiagnosticCodes.UNSUPPORTED_MODE)
                .with("profile", profile.name())
                .with("mode", mode);
        if (cause != null) {
            builder.cause(cause);
        }
        Diagnostic diagnostic = builder.build();
        diagnosticSink.emit(diagnostic);
        return new DiagnosticException(diagnostic);
    }

    private String charset(IocProperties.Sink.Csv csv) {
        return csv.charset() == null || csv.charset().isBlank() ? "UTF-8" : csv.charset();
    }

    private ExportFormat format(IocProperties.Sink.Csv csv) {
        String charset = charset(csv);
        try {
            Charset.forName(charset);
        } catch (IllegalArgumentException invalidCharset) {
            throw new IllegalArgumentException("Unsupported export charset: " + charset, invalidCharset);
        }
        if (csv.delimiter().length() != 1 || csv.quote().length() != 1) {
            throw new IllegalArgumentException("Export CSV delimiter and quote must be one character");
        }
        char delimiter = csv.delimiter().charAt(0);
        char quote = csv.quote().charAt(0);
        if (delimiter == quote || delimiter == '\r' || delimiter == '\n'
                || quote == '\r' || quote == '\n') {
            throw new IllegalArgumentException("Export CSV delimiter and quote must be distinct non-line-breaks");
        }
        return new ExportFormat("csv", charset, csv.delimiter(), csv.quote(), csv.nullLiteral());
    }

    /** Fingerprints every configured mapping input that can alter future canonical bytes. */
    private String mappingHash(IocProperties.Sink.Artifact artifact) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "mapping:v1");
            artifact.accepts().forEach(type -> add(digest, type.name()));
            addAll(digest, artifact.include());
            addAll(digest, artifact.exclude());
            IocProperties.Sink.Artifact.Id id = artifact.id();
            add(digest, id == null ? null : id.strategy());
            add(digest, id == null ? null : id.start());
            for (IocProperties.Sink.Artifact.Column column : artifact.columns()) {
                add(digest, column.name());
                add(digest, column.from());
                add(digest, column.value());
                add(digest, column.type());
                add(digest, column.whenType() == null ? null : column.whenType().name());
                addAll(digest, column.transform());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private void addAll(MessageDigest digest, List<?> values) {
        if (values == null) {
            add(digest, null);
            return;
        }
        add(digest, Integer.toString(values.size()));
        values.forEach(value -> add(digest, Objects.toString(value, null)));
    }

    private void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
