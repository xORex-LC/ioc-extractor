package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import com.iocextractor.domain.model.IndicatorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        @NotNull @Valid DataframeImport dataframeImport,
        @NotNull @Valid Export export,
        @NotNull @Valid Sync sync,
        @Valid Maintenance maintenance,
        @Valid Lifecycle lifecycle,
        @NotNull @Valid Observability observability) {

    @ConstructorBinding
    public IocProperties {
        patterns = snapshotMap(patterns);
        pipeline = pipeline == null ? new Pipeline(true, PipelineFailurePolicy.FAIL_FAST, 10_000) : pipeline;
        lifecycle = lifecycle == null ? Lifecycle.defaults() : lifecycle;
        dataframeImport = dataframeImport == null ? DataframeImport.disabled() : dataframeImport;
    }

    private static <T> List<T> snapshotList(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static <K, V> Map<K, V> snapshotMap(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <T> List<T> readOnly(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(source);
    }

    private static <K, V> Map<K, V> readOnly(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(source);
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

        public Source {
            sectionMarkers = snapshotList(sectionMarkers);
        }
    }

    public record Refang(@NotNull @Valid List<Rule> rules) {

        public Refang {
            rules = snapshotList(rules);
        }

        public record Rule(@NotNull String from, @NotNull String to) {
        }
    }

    public record Classify(@NotEmpty @Valid List<Rule> rules) {

        public Classify {
            rules = snapshotList(rules);
        }

        public record Rule(@NotNull List<String> when, @NotBlank String urlMatch, String hostMatch) {

            public Rule {
                when = snapshotList(when);
            }
        }
    }

    /** Pipeline policy switches. Deduplication here is batch-local only. */
    public record Pipeline(boolean deduplicate,
                           @NotNull PipelineFailurePolicy failurePolicy,
                           @Positive int maxDiagnosticsPerRun) {
    }

    public record Sink(@NotNull @Valid Csv csv, @NotEmpty @Valid List<Artifact> artifacts) {

        public Sink {
            artifacts = snapshotList(artifacts);
        }

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

            public Artifact {
                accepts = snapshotList(accepts);
                include = snapshotList(include);
                exclude = snapshotList(exclude);
                columns = snapshotList(columns);
            }

            public record Id(ArtifactIdStrategy strategy, IdStart start) {
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

                public Column {
                    transform = snapshotList(transform);
                }
            }
        }
    }

    public record ArtifactIdentity(@NotEmpty @Valid List<Artifact> artifacts) {

        public ArtifactIdentity {
            artifacts = snapshotList(artifacts);
        }

        public record Artifact(
                @NotBlank String name,
                @NotEmpty List<String> keyColumns,
                ArtifactKeyMode keyMode,
                @Positive Integer epoch,
                String recordKey,
                @Valid List<MatchKey> matchKeys) {

            public Artifact {
                keyColumns = snapshotList(keyColumns);
                matchKeys = snapshotList(matchKeys);
            }

            /** Returns the immutable match-key snapshot. */
            @Override
            public List<MatchKey> matchKeys() {
                return readOnly(matchKeys);
            }

            /** Named active-record matching alternative used independently from row identity. */
            public record MatchKey(@NotBlank String name, @NotEmpty List<String> keyColumns) {

                public MatchKey {
                    keyColumns = snapshotList(keyColumns);
                }

                /** Returns the immutable key-column snapshot. */
                @Override
                public List<String> keyColumns() {
                    return readOnly(keyColumns);
                }
            }
        }
    }

    /** Declarative managed dataframe-import catalog. Runtime intake is disabled by default. */
    public record DataframeImport(
            boolean enabled,
            @NotNull @Valid List<SourceDefinition> sources,
            @NotNull @Valid List<AuthorityProfile> authorityProfiles,
            @NotNull @Valid List<Contract> contracts,
            @NotNull @Valid RuntimeSettings runtime) {

        public DataframeImport {
            sources = snapshotList(sources);
            authorityProfiles = snapshotList(authorityProfiles);
            contracts = snapshotList(contracts);
            runtime = runtime == null ? RuntimeSettings.defaults() : runtime;
        }

        /** Returns the immutable source snapshot. */
        @Override
        public List<SourceDefinition> sources() {
            return readOnly(sources);
        }

        /** Returns the immutable authority-profile snapshot. */
        @Override
        public List<AuthorityProfile> authorityProfiles() {
            return readOnly(authorityProfiles);
        }

        /** Returns the immutable contract snapshot. */
        @Override
        public List<Contract> contracts() {
            return readOnly(contracts);
        }

        private static DataframeImport disabled() {
            return new DataframeImport(false, List.of(), List.of(), List.of(), RuntimeSettings.defaults());
        }

        /** Local intake, loss-tolerant detection and bounded snapshot settings. */
        public record RuntimeSettings(
                @NotNull @Valid Directories dirs,
                @NotNull @Valid Detect detect,
                @NotNull @Valid Stability stability,
                @NotNull @Valid Retry retry,
                @NotNull @Valid Limits limits,
                @NotNull @Valid Retention retention,
                @NotNull Duration shutdownTimeout) {

            public RuntimeSettings {
                dirs = dirs == null ? Directories.defaults() : dirs;
                detect = detect == null ? Detect.defaults() : detect;
                stability = stability == null ? Stability.defaults() : stability;
                retry = retry == null ? Retry.defaults() : retry;
                limits = limits == null ? Limits.defaults() : limits;
                retention = retention == null ? Retention.defaults() : retention;
                shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(30) : shutdownTimeout;
            }

            @AssertTrue(message = "shutdown-timeout must be positive")
            public boolean isShutdownTimeoutValid() {
                return shutdownTimeout == null || shutdownTimeout.isPositive();
            }

            private static RuntimeSettings defaults() {
                return new RuntimeSettings(null, null, null, null, null, null, null);
            }
        }

        /** Private managed-import filesystem namespace. */
        public record Directories(
                @NotBlank String processing,
                @NotBlank String snapshots,
                @NotBlank String staging,
                @NotBlank String terminal,
                @NotBlank String quarantine) {

            private static Directories defaults() {
                return new Directories("./var/import/processing", "./var/import/snapshots",
                        "./var/import/staging", "./var/import/terminal", "./var/import/quarantine");
            }
        }

        /** Poll correctness backstop and optional watch latency hint. */
        public record Detect(boolean useWatchService,
                             boolean useChangeNotifications,
                             @NotNull Duration reconcileInterval) {

            @AssertTrue(message = "must be positive")
            public boolean isReconcileIntervalValid() {
                return reconcileInterval == null || reconcileInterval.isPositive();
            }

            private static Detect defaults() {
                return new Detect(false, true, Duration.ofSeconds(5));
            }
        }

        /** Producer quiescence required before candidate admission. */
        public record Stability(@NotNull Duration quietPeriod) {

            @AssertTrue(message = "must not be negative")
            public boolean isQuietPeriodValid() {
                return quietPeriod == null || !quietPeriod.isNegative();
            }

            private static Stability defaults() {
                return new Stability(Duration.ofSeconds(2));
            }
        }

        /** Durable, non-blocking admission retry schedule. */
        public record Retry(@NotNull Duration delay) {

            @AssertTrue(message = "must not be negative")
            public boolean isDelayValid() {
                return delay == null || !delay.isNegative();
            }

            private static Retry defaults() {
                return new Retry(Duration.ofSeconds(5));
            }
        }

        /** Snapshot and bounded recovery limits. */
        public record Limits(@Positive long maximumSnapshotBytes, @Positive int recoveryBatchSize) {

            private static Limits defaults() {
                return new Limits(256L * 1024 * 1024, 100);
            }
        }

        /** Terminal evidence retention using the common max-age/count/action vocabulary. */
        public record Retention(@NotNull @Valid Target successful,
                                @NotNull @Valid Target unsuccessful,
                                @NotNull Duration interval,
                                @Positive int batchSize) {

            @AssertTrue(message = "interval must be positive")
            public boolean isIntervalValid() {
                return interval == null || interval.isPositive();
            }

            private static Retention defaults() {
                return new Retention(Target.deleteAfter(Duration.ofDays(30)),
                        Target.deleteAfter(Duration.ofDays(90)),
                        Duration.ofHours(1), 100);
            }

            /** One terminal outcome group governed by the common retention policy. */
            public record Target(Duration maxAge,
                                 @PositiveOrZero int maxCount,
                                 @NotNull RetentionActionType action,
                                 String archiveDir) {

                @AssertTrue(message = "must enable max-age or max-count and configure archive-dir for archive")
                public boolean isPolicyValid() {
                    boolean ageEnabled = maxAge != null && maxAge.isPositive();
                    boolean countEnabled = maxCount > 0;
                    boolean ageValid = maxAge == null || !maxAge.isNegative();
                    boolean archiveValid = action == null
                            || action != RetentionActionType.ARCHIVE
                            || archiveDir != null && !archiveDir.isBlank();
                    return ageValid && (ageEnabled || countEnabled) && archiveValid;
                }

                private static Target deleteAfter(Duration maxAge) {
                    return new Target(maxAge, 0, RetentionActionType.DELETE, null);
                }
            }
        }

        /** One local or SMB import source and its contract/authority allowlist. */
        public record SourceDefinition(String id,
                                       ImportSourceTransport transport,
                                       String location,
                                       String endpoint,
                                       List<String> contracts,
                                       String authority) {

            public SourceDefinition {
                contracts = snapshotList(contracts);
            }

            /** Returns the immutable contract allowlist. */
            @Override
            public List<String> contracts() {
                return readOnly(contracts);
            }
        }

        /** Maximum mutation authority granted to one source trust boundary. */
        public record AuthorityProfile(String id,
                                       List<String> artifacts,
                                       ImportMergePolicy maximumMergePolicy,
                                       boolean allowRelatedRouting,
                                       boolean allowMachineOnlyFormulaPreserve) {

            public AuthorityProfile {
                artifacts = snapshotList(artifacts);
            }

            /** Returns the immutable artifact allowlist. */
            @Override
            public List<String> artifacts() {
                return readOnly(artifacts);
            }
        }

        /** Versioned structural recognition, mapping and policy contract. */
        public record Contract(String id,
                               int version,
                               String charset,
                               @Valid Dialect dialect,
                               @Valid Recognition recognition,
                               ImportProcessingMode mode,
                               ImportRoutingPolicy routing,
                               ImportRowFailurePolicy rowFailurePolicy,
                               ImportDuplicatePolicy duplicatePolicy,
                               boolean renewUnchanged,
                               ImportFormulaPolicy formulaPolicy,
                               ImportMergePolicy mergeDefault,
                               @Valid List<Artifact> artifacts,
                               @Valid RequestedSlot requestedSlot) {

            public Contract {
                artifacts = snapshotList(artifacts);
            }

            /** Returns the immutable artifact-mapping snapshot. */
            @Override
            public List<Artifact> artifacts() {
                return readOnly(artifacts);
            }
        }

        /** Strict library-neutral CSV dialect. */
        public record Dialect(String delimiter,
                              String quote,
                              ImportRecordSeparator recordSeparator,
                              boolean headerRequired,
                              List<String> nullLiterals) {

            public Dialect {
                nullLiterals = nullLiterals == null ? List.of() : snapshotList(nullLiterals);
            }

            /** Returns the immutable null-literal snapshot. */
            @Override
            public List<String> nullLiterals() {
                return readOnly(nullLiterals);
            }
        }

        /** Exact structural recognition signature independent of file name and column order. */
        public record Recognition(List<String> requiredColumns,
                                  List<String> optionalColumns,
                                  List<String> ignoredColumns,
                                  Map<String, String> aliases) {

            public Recognition {
                requiredColumns = snapshotList(requiredColumns);
                optionalColumns = optionalColumns == null ? List.of() : snapshotList(optionalColumns);
                ignoredColumns = ignoredColumns == null ? List.of() : snapshotList(ignoredColumns);
                aliases = aliases == null ? Map.of() : snapshotMap(aliases);
            }

            /** Returns the immutable required-column snapshot. */
            @Override
            public List<String> requiredColumns() {
                return readOnly(requiredColumns);
            }

            /** Returns the immutable optional-column snapshot. */
            @Override
            public List<String> optionalColumns() {
                return readOnly(optionalColumns);
            }

            /** Returns the immutable ignored-column snapshot. */
            @Override
            public List<String> ignoredColumns() {
                return readOnly(ignoredColumns);
            }

            /** Returns the immutable alias snapshot. */
            @Override
            public Map<String, String> aliases() {
                return readOnly(aliases);
            }
        }

        /** Primary or related artifact mapping. */
        public record Artifact(String name,
                               ImportArtifactRole role,
                               String recordKey,
                               List<String> matchKeys,
                               ImportMergePolicy mergeDefault,
                               @Valid List<Column> columns) {

            public Artifact {
                matchKeys = snapshotList(matchKeys);
                columns = snapshotList(columns);
            }

            /** Returns the immutable match-key snapshot. */
            @Override
            public List<String> matchKeys() {
                return readOnly(matchKeys);
            }

            /** Returns the immutable column-mapping snapshot. */
            @Override
            public List<Column> columns() {
                return readOnly(columns);
            }
        }

        /** One target-column mapping and ordered transform chain. */
        public record Column(String target,
                             String source,
                             List<String> transforms,
                             ImportMergePolicy mergePolicy) {

            public Column {
                transforms = transforms == null ? List.of() : snapshotList(transforms);
            }

            /** Returns the immutable ordered transform snapshot. */
            @Override
            public List<String> transforms() {
                return readOnly(transforms);
            }
        }

        /** Optional requested external export-slot mapping. */
        public record RequestedSlot(String sourceColumn,
                                    String profile,
                                    ImportExistingSlotPolicy existingRecordPolicy) {
        }
    }

    /** Immutable local artifact export profiles and future scheduling/retention policy. */
    public record Export(
            boolean enabled,
            @NotBlank String root,
            @NotNull @Valid Trigger trigger,
            @NotEmpty @Valid List<Profile> profiles,
            @NotNull @Valid Retention retention) {

        public Export {
            profiles = snapshotList(profiles);
        }

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

            public Profile {
                artifacts = snapshotList(artifacts);
            }
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
                              @NotNull SmbEncryptionMode encryption,
                              Duration connectTimeout,
                              Duration requestTimeout,
                              Duration idleTimeout) {
                public Smb {
                    encryption = encryption == null ? SmbEncryptionMode.REQUIRED : encryption;
                }
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

            public Retention {
                targets = snapshotList(targets);
            }

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

    /** Runtime safety and bounded maintenance settings for canonical lifecycle data. */
    public record Lifecycle(@NotNull @Valid Validity validity,
                            @NotNull Duration historyRetention,
                            @NotNull Duration historyCleanupInterval,
                            @NotNull Duration receiptRetention,
                            @NotNull @Valid Reconcile reconcile,
                            @NotNull @Valid ClockSafety clock) {

        private static Lifecycle defaults() {
            return new Lifecycle(new Validity(
                    LifecycleValidityMode.DISABLED, Duration.ofHours(12), ExistingRecordsPolicy.REJECT),
                    Duration.ofDays(30), Duration.ofHours(1), Duration.ofDays(30),
                    new Reconcile(Duration.ofSeconds(5), 1_000),
                    new ClockSafety(Duration.ofSeconds(2), Duration.ofSeconds(30)));
        }

        public record Validity(@NotNull LifecycleValidityMode mode,
                               Duration fixedTtl,
                               @NotNull ExistingRecordsPolicy existingRecords) {
        }

        public record Reconcile(@NotNull Duration backstopInterval,
                                @Positive int batchSize) {
        }

        public record ClockSafety(@NotNull Duration maxBackwardSkew,
                                  @NotNull Duration maxClampDuration) {
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

            public Patterns {
                include = snapshotList(include);
                exclude = snapshotList(exclude);
            }
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
