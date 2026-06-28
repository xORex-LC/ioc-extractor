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
        @NotNull @Valid Lookup lookup,
        @NotNull @Valid Ingestion ingestion,
        @NotNull @Valid ArtifactIdentity artifactIdentity,
        @NotNull @Valid Export export,
        @NotNull @Valid Sync sync,
        @Valid Maintenance maintenance,
        @NotNull @Valid Observability observability) {

    public IocProperties {
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

    public record Lookup(String type, @NotBlank String path, boolean deduplicate, List<Artifact> artifacts) {
        public record Artifact(@NotBlank String name, @NotBlank String path) {
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
            unique(endpoints.stream().map(Endpoint::name).toList(), "sync endpoint");
            Set<String> endpointNames = new HashSet<>(endpoints.stream().map(Endpoint::name).toList());
            fetch.validate(endpointNames);
            publish.validate(endpointNames);
        }

        void validateAgainst(Export export) {
            Set<String> profiles = new HashSet<>(export.profiles().stream().map(Export.Profile::name).toList());
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
                              boolean encrypt) {

                public Smb {
                    host = requireText(host, "sync SMB host");
                    share = requireText(share, "sync SMB share");
                    username = requireText(username, "sync SMB username");
                    password = requireText(password, "sync SMB password");
                }
            }
        }

        public record Fetch(boolean enabled,
                            @NotNull Duration interval,
                            @NotNull @Valid List<Source> sources) {

            public Fetch {
                positive(interval, "sync fetch interval");
                sources = List.copyOf(sources);
                unique(sources.stream().map(Source::name).toList(), "sync fetch source");
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
                                 @NotNull List<String> exclude) {

                public Source {
                    name = requireText(name, "sync fetch source name");
                    endpoint = requireText(endpoint, "sync fetch source endpoint");
                    remotePath = requireText(remotePath, "sync fetch source remotePath");
                    include = List.copyOf(include);
                    exclude = List.copyOf(exclude);
                }
            }
        }

        public record Publish(boolean enabled,
                              @NotBlank String trigger,
                              @NotNull Duration interval,
                              @NotNull @Valid List<Target> targets) {

            public Publish {
                if (!Set.of("on-new-output", "interval", "both").contains(trigger)) {
                    throw new IllegalArgumentException("Unsupported sync publish trigger: " + trigger);
                }
                positive(interval, "sync publish interval");
                targets = List.copyOf(targets);
                unique(targets.stream().map(Target::name).toList(), "sync publish target");
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
}
