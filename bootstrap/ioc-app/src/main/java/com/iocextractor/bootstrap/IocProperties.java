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
import java.util.List;
import java.util.Map;

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
        @NotNull EngineType engine,
        @NotNull @Valid Runtime runtime,
        @NotNull @Valid Storage storage,
        @NotNull @Valid Source source,
        @NotNull @Valid Refang refang,
        @NotEmpty Map<IndicatorType, String> patterns,
        @NotNull @Valid Classify classify,
        @NotNull @Valid Sink sink,
        @NotNull @Valid Pipeline pipeline,
        @NotNull @Valid Ingestion ingestion,
        @NotNull @Valid ArtifactIdentity artifactIdentity,
        @NotNull @Valid Export export,
        @NotNull @Valid Sync sync,
        @Valid Maintenance maintenance,
        @NotNull @Valid Observability observability) {

    public IocProperties {
        pipeline = pipeline == null ? new Pipeline(true) : pipeline;
    }

    public record Runtime(@NotNull RuntimeMode mode) {
    }

    /**
     * Storage topology and backend-specific tuning. Runtime code selects a role
     * ({@code service}, later {@code dataframe}) through ports; SQL/JDBC details
     * remain in storage adapters.
     */
    public record Storage(@NotNull @Valid Service service, @NotNull @Valid Dataframe dataframe) {

        public record Service(
                @NotNull StorageType type,
                @NotBlank String url,
                @NotNull @Valid Sqlite sqlite,
                @NotNull @Valid Pool pool) {
        }

        public record Dataframe(
                @NotNull StorageType type,
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

            public record Id(ArtifactIdStrategy strategy, String start) {
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

    public record ArtifactIdentity(@NotEmpty @Valid List<Artifact> artifacts) {

        public record Artifact(
                @NotBlank String name,
                @NotEmpty List<String> keyColumns,
                ArtifactKeyMode keyMode,
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
                @NotNull ExportTriggerType type,
                @NotNull Duration interval,
                Duration quietPeriod,
                Duration maxCap) {
        }

        public record Profile(
                @NotBlank String name,
                @NotNull ExportOutputMode outputMode,
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
            endpoints = endpoints == null ? null : List.copyOf(endpoints);
        }

        public record Retry(int maxAttempts,
                            @NotNull Duration backoff,
                            double multiplier,
                            @NotNull Duration maxBackoff,
            boolean jitter) {
        }

        public record Endpoint(@NotBlank String name,
                               @NotNull SyncTransport transport,
                               @Valid Smb smb) {
            public record Smb(@NotBlank String host,
                              @NotBlank String share,
                              String domain,
                              @NotBlank String username,
                              @NotBlank String password,
                              boolean encrypt,
                              Duration connectTimeout,
                              Duration requestTimeout,
                              Duration idleTimeout) {
            }
        }

        public record Fetch(boolean enabled,
                            @NotNull Duration interval,
                            @NotNull @Valid List<Source> sources) {

            public Fetch {
                sources = sources == null ? null : List.copyOf(sources);
            }

            public record Source(@NotBlank String name,
                                 @NotBlank String endpoint,
                                 @NotBlank String remotePath,
                                 @NotNull List<String> include,
                                 @NotNull List<String> exclude,
                                 @Valid ChangeNotify changeNotify) {

                public Source {
                    include = include == null ? null : List.copyOf(include);
                    exclude = exclude == null ? null : List.copyOf(exclude);
                    changeNotify = changeNotify == null ? ChangeNotify.disabled() : changeNotify;
                }

                public record ChangeNotify(boolean enabled, Duration debounce) {

                    private static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(3);

                    public ChangeNotify {
                        debounce = debounce == null ? DEFAULT_DEBOUNCE : debounce;
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
                targets = targets == null ? null : List.copyOf(targets);
            }

            public record Target(@NotBlank String name,
                                 @NotBlank String endpoint,
                                 @NotBlank String remotePath,
                                 @NotBlank String exportProfile) {
            }
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
                    RetentionActionType action,
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

        public record Ledger(@NotNull IngestionLedgerType type, @NotBlank String path) {
        }
    }

    public record Observability(@NotNull ObservabilityMode mode, boolean perItemTraceEnabled) {
    }
}
