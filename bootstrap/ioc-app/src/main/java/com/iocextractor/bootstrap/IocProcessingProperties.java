package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operator syntax for named IOC processing plans; document selection is required. */
public record IocProcessingProperties(String documentPlan, List<Plan> plans,
                                      @jakarta.validation.Valid Workspace workspace) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public IocProcessingProperties {
        plans = snapshot(plans);
        workspace = workspace == null ? Workspace.defaults() : workspace;
    }

    public IocProcessingProperties(String documentPlan, List<Plan> plans) {
        this(documentPlan, plans, Workspace.defaults());
    }

    /** Preparation admission budgets shared across jobs; these do not set the JVM heap or RSS limit. */
    public record Workspace(@jakarta.validation.constraints.NotBlank String directory,
                            @jakarta.validation.constraints.Positive long memoryBytes,
                            @jakarta.validation.constraints.Min(64) int cacheKib,
                            @jakarta.validation.constraints.Positive int maximumRowBytes,
                            @jakarta.validation.constraints.Positive int maximumFieldBytes,
                            @jakarta.validation.constraints.Positive long workspaceBytes,
                            @jakarta.validation.constraints.Positive long totalDiskBytes,
                            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(4096) int batchRows,
                            @jakarta.validation.constraints.NotNull java.time.Duration retention) {
        private static final int KIB = 1024;
        private static final long MIB = KIB * (long) KIB;
        private static final long GIB = MIB * KIB;
        private static final long DEFAULT_MEMORY_BYTES = 64 * MIB;
        private static final int DEFAULT_CACHE_KIB = 4096;
        private static final int DEFAULT_ROW_BYTES = 256 * KIB;
        private static final int DEFAULT_FIELD_BYTES = 64 * KIB;
        private static final long DEFAULT_WORKSPACE_BYTES = 2 * GIB;
        private static final long DEFAULT_DISK_BYTES = 8 * GIB;
        private static final int DEFAULT_BATCH_ROWS = 128;
        private static final int MAXIMUM_ROW_BYTES = 16 * KIB * KIB;
        private static final long MINIMUM_WORKSPACE_BYTES = 64 * KIB;
        private static final long WORKING_ROW_ALLOWANCE = 64;
        private static final long FIXED_BUFFER_BYTES = 64 * KIB;

        static Workspace defaults() {
            return new Workspace("./var/document-preparation", DEFAULT_MEMORY_BYTES, DEFAULT_CACHE_KIB,
                    DEFAULT_ROW_BYTES, DEFAULT_FIELD_BYTES, DEFAULT_WORKSPACE_BYTES, DEFAULT_DISK_BYTES,
                    DEFAULT_BATCH_ROWS, java.time.Duration.ofDays(1));
        }

        @jakarta.validation.constraints.AssertTrue(message = "Document workspace budgets are inconsistent")
        // Mirrors DocumentPreparationLimits without throwing during collect-all config validation.
        // Working space is an admission estimate, not a measurement of Java object sizes.
        public boolean isBudgetValid() {
            return retention != null && !retention.isNegative() && !retention.isZero()
                    && maximumRowBytes >= maximumFieldBytes && maximumRowBytes <= MAXIMUM_ROW_BYTES
                    && workspaceBytes >= MINIMUM_WORKSPACE_BYTES && totalDiskBytes >= workspaceBytes
                    && memoryBytes >= cacheKib * (long) KIB + maximumRowBytes * WORKING_ROW_ALLOWANCE + FIXED_BUFFER_BYTES;
        }
    }

    static IocProcessingProperties unconfigured() {
        return new IocProcessingProperties(null, List.of());
    }

    public List<Plan> plans() {
        return snapshot(plans);
    }

    private static <T> List<T> snapshot(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <K, V> Map<K, V> snapshot(Map<K, V> values) {
        return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public record Plan(String name, List<View> views, List<Classification> classifications,
                       Routing routing, List<String> omittedArtifacts,
                       ObservationSelection observationSelection) {
        public Plan(String name, List<View> views, List<Classification> classifications,
                    Routing routing, List<String> omittedArtifacts) {
            this(name, views, classifications, routing, omittedArtifacts, ObservationSelection.FINAL_KEY);
        }

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Plan {
            views = snapshot(views);
            classifications = snapshot(classifications);
            omittedArtifacts = snapshot(omittedArtifacts);
            observationSelection = observationSelection == null
                    ? ObservationSelection.FINAL_KEY : observationSelection;
        }

        public List<View> views() {
            return snapshot(views);
        }

        public List<Classification> classifications() {
            return snapshot(classifications);
        }

        public List<String> omittedArtifacts() {
            return snapshot(omittedArtifacts);
        }
    }

    public enum ObservationSelection { RETAINED_OBSERVATIONS, FINAL_KEY }

    public record View(String name, String operation, String input, RecoveryArguments arguments) {
    }

    public record RecoveryArguments(List<String> onReasons, String useView) {
        public RecoveryArguments {
            onReasons = snapshot(onReasons);
        }

        public List<String> onReasons() {
            return snapshot(onReasons);
        }
    }

    public record Classification(String view, String policy) {
    }

    public record Routing(Mode mode, OnUnmatched onUnmatched, List<Branch> branches,
                          Branch defaultBranch) {
        public Routing {
            branches = snapshot(branches);
        }

        public List<Branch> branches() {
            return snapshot(branches);
        }
    }

    public enum Mode { FIRST, ALL, EXCLUSIVE }

    public record OnUnmatched(Action action, String branch) {
    }

    public enum Action { SKIP, REJECT, ROUTE }

    public record Branch(String id, String artifact, String defaultView,
                         Condition eligibility, Map<String, String> fieldViews) {
        public Branch {
            fieldViews = snapshot(fieldViews);
        }

        public Map<String, String> fieldViews() {
            return snapshot(fieldViews);
        }
    }

    /** Exactly one of leaf, all, any or not is allowed by semantic admission. */
    public record Condition(String on, String predicate, PredicateArguments arguments,
                            List<Condition> all, List<Condition> any, Condition not) {
        /** Bind the singular recursive child through a map; keep the admitted model typed. */
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Condition(@org.springframework.boot.context.properties.bind.Name("not")
                         Map<String, Object> negation, String on, String predicate,
                         PredicateArguments arguments, List<Condition> all, List<Condition> any) {
            this(on, predicate, arguments, all, any, ProcessingConditionBinding.bind(negation));
        }

        public Condition {
            all = snapshot(all);
            any = snapshot(any);
        }

        public List<Condition> all() {
            return snapshot(all);
        }

        public List<Condition> any() {
            return snapshot(any);
        }
    }

    /** Typed registry arguments; only type-in currently accepts parameters. */
    public record PredicateArguments(List<IndicatorType> types) {
        public PredicateArguments {
            types = snapshot(types);
        }

        public List<IndicatorType> types() {
            return snapshot(types);
        }
    }
}
