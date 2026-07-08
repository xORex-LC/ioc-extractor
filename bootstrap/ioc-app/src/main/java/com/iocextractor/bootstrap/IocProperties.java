package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type-safe binding of the {@code ioc.*} configuration tree. This is the only
 * place the external configuration shape is known; the domain stays config-free.
 *
 * <p>{@link Validated} makes an incomplete configuration fail fast at startup
 * with a clear message, rather than surfacing as an obscure NPE later.
 */
@Validated
@ConfigurationProperties(prefix = "ioc")
public record IocProperties(
        String engine,
        @NotNull @Valid Runtime runtime,
        @NotNull @Valid Storage storage,
        @NotNull @Valid Source source,
        @NotNull @Valid Refang refang,
        @NotEmpty Map<IndicatorType, String> patterns,
        @NotNull @Valid Classify classify,
        @NotNull @Valid Sink sink,
        @NotNull @Valid Pipeline pipeline,
        @Valid Lookup lookup,
        @NotNull @Valid Ingestion ingestion,
        @NotNull @Valid ArtifactIdentity artifactIdentity,
        @NotNull @Valid Export export,
        @NotNull @Valid Sync sync,
        @Valid Maintenance maintenance,
        @NotNull @Valid Observability observability) {

    public IocProperties {
        pipeline = pipeline == null ? new Pipeline(true) : pipeline;
        validateDataframeStorage(storage);
        rejectLegacyLookup(lookup);
        validateSinkIdStarts(sink);
        if (sync != null && export != null) {
            sync.validateAgainst(export);
        }
    }

    public record Runtime(@NotBlank String mode) {
    }

    /**
     * Storage topology and backend-specific tuning. Runtime code selects a role
     * ({@code service}, later {@code dataframe}) through ports; SQL/JDBC details
     * remain in storage adapters.
     */
    public record Storage(@NotNull @Valid Service service, @NotNull @Valid Dataframe dataframe) {

        public record Service(
                @NotBlank String type,
                @NotBlank String url,
                @NotNull @Valid Sqlite sqlite,
                @NotNull @Valid Pool pool) {
        }

        public record Dataframe(
                @NotBlank String type,
                @NotBlank String url,
                @NotNull @Valid Sqlite sqlite,
                @NotNull @Valid Pool pool) {
        }

        public record Sqlite(@NotBlank String tuning) {
        }

        public record Pool(@Positive int writeMax, @Positive int readMax) {
        }
    }

    public record Source(@NotBlank String type, String charset, @NotNull List<String> sectionMarkers) {
    }

    public record Refang(@NotNull @Valid List<Rule> rules) {
        public record Rule(@NotNull String from, @NotNull String to) {
        }
    }

    public record Classify(@NotEmpty @Valid List<Rule> rules) {
        public record Rule(@NotNull List<String> when, @NotBlank String urlMatch, String hostMatch) {
        }
    }

    /** Pipeline policy switches. Deduplication here is batch-local only. */
    public record Pipeline(boolean deduplicate) {
    }

    public record Sink(@NotNull @Valid Csv csv, @NotEmpty @Valid List<Artifact> artifacts) {

        public record Csv(@NotBlank String delimiter, @NotBlank String quote, @NotBlank String nullLiteral,
                          String charset) {
        }

        public record Artifact(
                @NotBlank String name,
                boolean enabled,
                @NotBlank String path,
                @NotEmpty List<IndicatorType> accepts,
                List<String> include,
                List<String> exclude,
                Id id,
                @NotEmpty @Valid List<Column> columns) {

            public record Id(String strategy, String start) {
            }

            public boolean hasPublicIdColumn() {
                return columns != null && columns.stream()
                        .anyMatch(column -> "id".equals(column.name()));
            }

            public record Column(
                    @NotBlank String name,
                    @NotBlank String from,
                    String value,
                    String type,
                    IndicatorType whenType,
                    List<String> transform) {
            }
        }
    }

    /**
     * Tombstone for removed {@code ioc.lookup.*} settings. Keep this nullable
     * until strict binding replaces compatibility diagnostics.
     */
    public record Lookup(String type, String path, Boolean deduplicate, List<Artifact> artifacts) {
        public record Artifact(String name, String path) {
        }
    }

    public record ArtifactIdentity(@NotEmpty @Valid List<Artifact> artifacts) {

        public record Artifact(
                @NotBlank String name,
                @NotEmpty List<String> keyColumns,
                String keyMode,
                @Positive Integer epoch) {
        }
    }

    /** Immutable local artifact export profiles and future scheduling/retention policy. */
    public record Export(
            boolean enabled,
            @NotBlank String root,
            @NotNull @Valid Trigger trigger,
            @NotEmpty @Valid List<Profile> profiles,
            @NotNull @Valid Retention retention) {

        public record Trigger(
                @NotBlank String type,
                @NotNull Duration interval,
                Duration quietPeriod,
                Duration maxCap) {
        }

        public record Profile(
                @NotBlank String name,
                @NotBlank String outputMode,
                @NotEmpty List<String> artifacts) {
        }

        public record Retention(Duration maxAge, @PositiveOrZero int maxCount) {
        }
    }

    /** Remote fetch/publish synchronization. Connection details remain adapter-owned. */
    public record Sync(
            boolean enabled,
            @NotNull @Valid Retry retry,
            @NotNull @Valid List<Endpoint> endpoints,
            @NotNull @Valid Fetch fetch,
            @NotNull @Valid Publish publish) {

        public Sync {
            endpoints = List.copyOf(endpoints);
            unique(endpoints.stream().map(endpoint -> endpoint.name()).toList(), "sync endpoint");
            Set<String> endpointNames = new HashSet<>(endpoints.stream().map(endpoint -> endpoint.name()).toList());
            fetch.validate(endpointNames);
            publish.validate(endpointNames);
        }

        void validateAgainst(Export export) {
            Set<String> profiles = new HashSet<>(export.profiles().stream().map(profile -> profile.name()).toList());
            for (Publish.Target target : publish.targets()) {
                if (!profiles.contains(target.exportProfile())) {
                    throw new IllegalArgumentException("Unknown sync publish export profile: "
                            + target.exportProfile());
                }
            }
        }

        public record Retry(@Positive int maxAttempts,
                            @NotNull Duration backoff,
                            double multiplier,
                            @NotNull Duration maxBackoff,
            boolean jitter) {

            public Retry {
                if (maxAttempts < 1) {
                    throw new IllegalArgumentException("sync retry maxAttempts must be at least 1");
                }
                positive(backoff, "sync retry backoff");
                positive(maxBackoff, "sync retry maxBackoff");
                if (multiplier < 1.0d) {
                    throw new IllegalArgumentException("sync retry multiplier must be at least 1.0");
                }
                if (maxBackoff.compareTo(backoff) < 0) {
                    throw new IllegalArgumentException("sync retry maxBackoff must be >= backoff");
                }
            }
        }

        public record Endpoint(@NotBlank String name,
                               @NotBlank String transport,
                               @Valid Smb smb) {

            public Endpoint {
                name = requireText(name, "sync endpoint name");
                transport = requireText(transport, "sync endpoint transport");
                if ("smb".equalsIgnoreCase(transport) && smb == null) {
                    throw new IllegalArgumentException("SMB sync endpoint requires smb settings: " + name);
                }
            }

            public record Smb(@NotBlank String host,
                              @NotBlank String share,
                              String domain,
                              @NotBlank String username,
                              @NotBlank String password,
                              boolean encrypt,
                              Duration connectTimeout,
                              Duration requestTimeout,
                              @Deprecated Duration readTimeout,
                              Duration idleTimeout) {

                public Smb {
                    host = requireText(host, "sync SMB host");
                    share = requireText(share, "sync SMB share");
                    username = requireText(username, "sync SMB username");
                    password = requireText(password, "sync SMB password");
                    optionalPositive(connectTimeout, "sync SMB connectTimeout");
                    optionalPositive(requestTimeout, "sync SMB requestTimeout");
                    optionalPositive(idleTimeout, "sync SMB idleTimeout");
                    if (readTimeout != null) {
                        throw new IllegalArgumentException(
                                "sync SMB readTimeout was removed; use requestTimeout instead");
                    }
                }
            }
        }

        public record Fetch(boolean enabled,
                            @NotNull Duration interval,
                            @NotNull @Valid List<Source> sources) {

            public Fetch {
                positive(interval, "sync fetch interval");
                sources = List.copyOf(sources);
                unique(sources.stream().map(source -> source.name()).toList(), "sync fetch source");
            }

            void validate(Set<String> endpointNames) {
                for (Source source : sources) {
                    requireEndpoint(endpointNames, source.endpoint(), "sync fetch source " + source.name());
                }
            }

            public record Source(@NotBlank String name,
                                 @NotBlank String endpoint,
                                 @NotBlank String remotePath,
                                 @NotNull List<String> include,
                                 @NotNull List<String> exclude,
                                 @Valid ChangeNotify changeNotify) {

                public Source {
                    name = requireText(name, "sync fetch source name");
                    endpoint = requireText(endpoint, "sync fetch source endpoint");
                    remotePath = requireText(remotePath, "sync fetch source remotePath");
                    include = List.copyOf(include);
                    exclude = List.copyOf(exclude);
                    changeNotify = changeNotify == null ? ChangeNotify.disabled() : changeNotify;
                }

                public record ChangeNotify(boolean enabled, Duration debounce) {

                    private static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(3);

                    public ChangeNotify {
                        debounce = debounce == null ? DEFAULT_DEBOUNCE : debounce;
                        positive(debounce, "sync fetch changeNotify debounce");
                    }

                    static ChangeNotify disabled() {
                        return new ChangeNotify(false, DEFAULT_DEBOUNCE);
                    }
                }
            }
        }

        public record Publish(boolean enabled,
                              @NotNull Duration interval,
                              @NotNull @Valid List<Target> targets) {

            public Publish {
                positive(interval, "sync publish interval");
                targets = List.copyOf(targets);
                unique(targets.stream().map(target -> target.name()).toList(), "sync publish target");
            }

            void validate(Set<String> endpointNames) {
                for (Target target : targets) {
                    requireEndpoint(endpointNames, target.endpoint(), "sync publish target " + target.name());
                }
            }

            public record Target(@NotBlank String name,
                                 @NotBlank String endpoint,
                                 @NotBlank String remotePath,
                                 @NotBlank String exportProfile) {

                public Target {
                    name = requireText(name, "sync publish target name");
                    endpoint = requireText(endpoint, "sync publish target endpoint");
                    remotePath = requireText(remotePath, "sync publish target remotePath");
                    exportProfile = requireText(exportProfile, "sync publish target exportProfile");
                }
            }
        }

        private static void requireEndpoint(Set<String> endpointNames, String endpoint, String owner) {
            if (!endpointNames.contains(endpoint)) {
                throw new IllegalArgumentException(owner + " references unknown endpoint: " + endpoint);
            }
        }

        private static void unique(List<String> names, String label) {
            Set<String> seen = new HashSet<>();
            for (String name : names) {
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("Duplicate " + label + " name: " + name);
                }
            }
        }

        private static void positive(Duration duration, String name) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        private static void optionalPositive(Duration duration, String name) {
            if (duration != null) {
                positive(duration, name);
            }
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    /**
     * Daemon housekeeping. A single {@link Retention} sweep reaps aged/over-count
     * entries from growing directories ({@code done}, {@code failed}); each
     * {@link Retention.Target} is configured declaratively.
     */
    public record Maintenance(@Valid Retention retention) {

        public record Retention(
                boolean enabled,
                Duration interval,
                Duration initialDelay,
                @Valid List<Target> targets) {

            public record Target(
                    @NotBlank String name,
                    @NotBlank String dir,
                    Duration maxAge,
                    int maxCount,
                    String action,
                    String archiveDir) {
            }
        }
    }

    public record Ingestion(
            @NotNull @Valid Dirs dirs,
            @NotNull @Valid Patterns patterns,
            @NotNull @Valid Detect detect,
            @NotNull @Valid Stability stability,
            @NotNull @Valid Retry retry,
            @NotNull @Valid Ledger ledger,
            int concurrency) {

        public record Dirs(
                @NotBlank String inbox,
                @NotBlank String processing,
                @NotBlank String done,
                @NotBlank String failed) {
        }

        public record Patterns(@NotEmpty List<String> include, @NotNull List<String> exclude) {
        }

        public record Detect(boolean useWatchService, @NotNull Duration reconcileInterval, int maxMessagesPerPoll) {
        }

        public record Stability(@NotNull Duration quietPeriod) {
        }

        public record Retry(int maxAttempts, @NotNull Duration backoff) {
        }

        public record Ledger(@NotBlank String type, @NotBlank String path) {
        }
    }

    public record Observability(@NotBlank String mode, boolean perItemTraceEnabled) {
    }

    private static void validateDataframeStorage(Storage storage) {
        if (storage == null || storage.dataframe() == null || storage.dataframe().type() == null) {
            return;
        }
        if (!"jdbc".equalsIgnoreCase(storage.dataframe().type())) {
            throw new IllegalArgumentException(
                    "ioc.storage.dataframe.type must be jdbc; legacy CSV dataframe storage was removed");
        }
    }

    private static void rejectLegacyLookup(Lookup lookup) {
        if (lookup == null) {
            return;
        }
        boolean hasMovedDedup = lookup.deduplicate() != null;
        boolean hasRemovedLookup = lookup.type() != null
                || lookup.path() != null
                || lookup.artifacts() != null;
        if (hasMovedDedup && hasRemovedLookup) {
            throw new IllegalArgumentException(
                    "legacy ioc.lookup.* removed; ioc.lookup.deduplicate moved to ioc.pipeline.deduplicate");
        }
        if (hasMovedDedup) {
            throw new IllegalArgumentException("ioc.lookup.deduplicate moved to ioc.pipeline.deduplicate");
        }
        if (hasRemovedLookup) {
            throw new IllegalArgumentException(
                    "legacy ioc.lookup.* removed; SQLite/JDBC dataframe storage is the only runtime truth");
        }
    }

    private static void validateSinkIdStarts(Sink sink) {
        if (sink == null || sink.artifacts() == null) {
            return;
        }
        for (Sink.Artifact artifact : sink.artifacts()) {
            Sink.Artifact.Id id = artifact.id();
            if (id == null || id.start() == null || artifact.hasPublicIdColumn() || !isExplicitNumeric(id.start())) {
                continue;
            }
            throw new IllegalArgumentException("Artifact " + artifact.name()
                    + " configures id.start but has no public id column");
        }
    }

    private static boolean isExplicitNumeric(String value) {
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
