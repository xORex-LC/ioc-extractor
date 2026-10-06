package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.platform.etl.Envelope;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * Test-only, opt-in entry counters and successful preparation timers for diagnostic forks.
 * Uses the existing Spring ASM dependency; never attached in primary timing forks.
 */
public final class ComparisonDiagnostics {
    private static final String HELPER = "com/iocextractor/bootstrap/ComparisonDiagnostics";
    private static final Map<String, String> ENTRIES = Map.ofEntries(
            entry("domain/feature/NetworkAddressParser", "parse", "parser_calls"),
            entry("domain/attribute/MarkerSourceAttributor", "attribute", "attribution_calls"),
            entry("domain/feature/NetworkHostDeriver", "derive", "host_computations"),
            entry("adapter/out/psl/PslHostClassifier", "classify", "psl_host_calls"),
            entry("processing/classification/IndicatorClassifier", "classify", "classifications"),
            entry("adapter/processing/camel/runtime/InvocationViews", "demand", "view_demands"),
            entry("adapter/processing/camel/runtime/InvocationViews", "evaluateOperation", "view_evaluations"),
            entry("bootstrap/IocProcessingOperations", "prepareBranch", "recipient_calls"),
            entry("adapter/out/sink/csv/CsvArtifactPreparer", "preparedRow", "artifact_candidates"),
            entry("adapter/processing/camel/compile/CompiledSelector", "evaluate", "branch_evaluations"),
            entry("processing/mapping/ConfigurableRowMapper", "cell", "mapped_cells"));
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, java.util.concurrent.atomic.AtomicLong> MAXIMA = new ConcurrentHashMap<>();
    private static final java.util.Set<String> TARGETS = java.util.stream.Stream.concat(
            ENTRIES.keySet().stream().map(name -> name.substring(0, name.lastIndexOf('.'))),
            java.util.stream.Stream.of(
                    "com/iocextractor/application/pipeline/stage/PrepareRoutedArtifactsStage",
                    "com/iocextractor/application/artifact/ArtifactIdSequence",
                    "com/iocextractor/bootstrap/RouterProcessedImportRowPreparer",
                    "com/iocextractor/adapter/processing/camel/runtime/CamelRouteRuntime",
                    "com/iocextractor/application/artifact/policy/ArtifactOccurrenceSelector$Accumulator"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static final ThreadLocal<Map<String, Long>> STARTS = ThreadLocal.withInitial(TreeMap::new);
    private static final ThreadLocal<Boolean> DERIVED = new ThreadLocal<>();
    private static volatile boolean installed;
    private static volatile boolean active;

    private ComparisonDiagnostics() { }

    /** Called by the JVM before application classes are loaded. */
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                    ProtectionDomain protection, byte[] bytes) {
                if (name == null || !(TARGETS.contains(name)
                        || name.startsWith("com/iocextractor/adapter/out/store/jdbc/"))) {
                    return null;
                }
                try {
                    return instrument(name, bytes);
                } catch (Throwable failure) {
                    // The JVM ignores transformer exceptions. Preserve them for the probe instead.
                    FAILURE.compareAndSet(null, failure);
                    return null;
                }
            }
        });
        installed = true;
    }

    static void begin() {
        if (!installed) {
            throw new IllegalStateException("Diagnostic fork requires the comparison Java agent");
        }
        COUNTS.clear();
        MAXIMA.clear();
        STARTS.remove();
        DERIVED.remove();
        active = true;
    }

    static String end() {
        active = false;
        if (FAILURE.get() != null) {
            throw new IllegalStateException("Comparison instrumentation failed", FAILURE.get());
        }
        if (Boolean.TRUE.equals(DERIVED.get())) {
            throw new IllegalStateException("Derived classification instrumentation did not complete");
        }
        DERIVED.remove();
        if (!STARTS.get().isEmpty()) {
            throw new IllegalStateException("Preparation instrumentation did not complete");
        }
        var result = new TreeMap<String, Long>();
        COUNTS.forEach((name, value) -> result.put(name, value.sum()));
        MAXIMA.forEach((name, value) -> result.put(name, value.get()));
        STARTS.remove();
        return result.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /** Entry hook; counters exclude startup, warm-up and shutdown. */
    public static void count(String name) {
        if (active) {
            COUNTS.computeIfAbsent(name, ignored -> new LongAdder()).increment();
        }
    }

    /** Timers cover successful top-level document preparation or each processed import row. */
    public static void start(String name) {
        if (active && STARTS.get().putIfAbsent(name, System.nanoTime()) != null) {
            throw new IllegalStateException("Unexpected recursive preparation: " + name);
        }
    }

    public static void finish(String name) {
        if (active) {
            Long start = STARTS.get().remove(name);
            if (start == null) {
                throw new IllegalStateException("Preparation timer has no start: " + name);
            }
            long elapsed = System.nanoTime() - start;
            COUNTS.computeIfAbsent(name, ignored -> new LongAdder()).add(elapsed);
            maximum("max_" + name, elapsed);
        }
    }

    /** Return hook at the preparation/checkpoint seam; multiplicity precedes ID reservation. */
    public static void prepared(Object value) {
        if (active) {
            PreparedArtifacts prepared = (PreparedArtifacts) ((Envelope<?>) value).payload();
            long rows = prepared.plans().stream().mapToLong(plan -> plan.rows().size()).sum();
            COUNTS.computeIfAbsent("prepared_rows", ignored -> new LongAdder()).add(rows);
            prepared.plans().forEach(plan -> COUNTS.computeIfAbsent("prepared_rows_" + plan.artifactName(),
                    ignored -> new LongAdder()).add(plan.rows().size()));
            maximum("max_prepared_rows", rows);
        }
    }

    public static void imported(Object value) {
        if (active) {
            var result = (ImportRowMappingResult) value;
            count(result.row().isPresent() ? "prepared_import_rows" : "rejected_import_rows");
            COUNTS.computeIfAbsent("import_warnings", ignored -> new LongAdder()).add(result.warnings().size());
        }
    }

    public static void cached(Map<?, ?> views) {
        if (active) {
            count("view_cache_writes");
            maximum("max_view_entries", views.size());
        }
    }

    /** Diagnostic-only origin tracking distinguishes requests from actual policy computations. */
    public static void derivedStart() {
        if (active) {
            DERIVED.set(true);
            count("derived_classification_requests");
        }
    }

    public static void derivedFinish() {
        DERIVED.remove();
    }

    public static void classified() {
        if (active && Boolean.TRUE.equals(DERIVED.get())) {
            count("derived_classifications");
        }
    }

    public static void retainedWinners(Map<?, ?> winners) {
        if (active) {
            count("winner_updates");
            maximum("max_winners_per_artifact", winners.size());
        }
    }

    private static void maximum(String name, long value) {
        MAXIMA.computeIfAbsent(name, ignored -> new java.util.concurrent.atomic.AtomicLong())
                .accumulateAndGet(value, Math::max);
    }

    /** Captures reserved counts and ranges independently of the final canonical CSV. */
    public static void reserved(Object value) {
        if (active) {
            ArtifactIdReservation reservation = (ArtifactIdReservation) value;
            COUNTS.computeIfAbsent("reserved_ids", ignored -> new LongAdder()).add(reservation.count());
            count("reservation_" + reservation.strategy() + "_" + reservation.start() + "_"
                    + reservation.count());
        }
    }

    private static Map.Entry<String, String> entry(String type, String method, String metric) {
        return Map.entry("com/iocextractor/" + type + "." + method, metric);
    }

    static byte[] instrument(String type, byte[] bytes) {
        var reader = new ClassReader(bytes);
        // Both historical row-local and attempt-scoped preparers use this observer.
        var methods = new java.util.HashSet<String>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor,
                                             String signature, String[] exceptions) {
                var delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                String counter = ENTRIES.get(type + "." + method);
                if (type.endsWith("/ConfigurableRowMapper") && method.equals("toRow")
                        && descriptor.contains("Ljava/util/Map;")) {
                    counter = "mapped_rows";
                }
                String entryCounter = counter;
                boolean documentPreparation = type.startsWith("com/iocextractor/application/pipeline/stage/")
                        && type.endsWith("/PrepareRoutedArtifactsStage")
                        && method.equals("process") && descriptor.endsWith("Lcom/iocextractor/platform/etl/Envelope;");
                boolean importPreparation = type.endsWith("/ProcessedImportRowPreparer")
                        || type.endsWith("/RouterProcessedImportRowPreparer");
                importPreparation &= method.equals(methods.contains("prepareInSession") ? "prepareInSession" : "prepare")
                        && descriptor.startsWith(
                        "(Lcom/iocextractor/application/dataframeimport/contract/CompiledDataframeImportContract;");
                String timer = documentPreparation || importPreparation ? "preparation_nanos" : null;
                boolean importReturn = importPreparation;
                boolean reservation = type.equals("com/iocextractor/application/artifact/ArtifactIdSequence")
                        && method.equals("reserve");
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (entryCounter != null) {
                            hook("count", entryCounter);
                        }
                        if (type.endsWith("/IndicatorClassifier") && method.equals("classify")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "classified", "()V", false);
                        }
                        if (timer != null) {
                            hook("start", timer);
                            hook("count", "preparation_calls");
                        }
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String desc, boolean isInterface) {
                        if (type.endsWith("/MarkerSourceAttributor") && owner.endsWith("/SourceMarker")
                                && name.equals("position")) {
                            hook("count", "attribution_marker_comparisons");
                        }
                        boolean admission = type.endsWith("/JdbcWriterAdmission")
                                && owner.endsWith("/JdbcWriterAdmission");
                        boolean canonicalWrite = type.endsWith("/JdbcCanonicalLifecycleWriter")
                                || type.endsWith("/JdbcCanonicalImportWriter");
                        boolean ownership = canonicalWrite && owner.endsWith("/JdbcLifecycleTransactions")
                                && name.equals("acquireActiveWriteOwnership");
                        if (admission && name.equals("enter")) {
                            hook("start", "writer_admission_wait_nanos");
                        }
                        if (admission && name.equals("leave")) {
                            hook("finish", "writer_admission_hold_nanos");
                        }
                        if (ownership) {
                            hook("start", "canonical_writer_wait_nanos");
                        }
                        if (owner.equals("java/sql/Connection") && name.equals("prepareStatement")) {
                            hook("count", "jdbc_statement_prepares");
                        }
                        if (owner.equals("java/sql/Connection") && name.equals("createStatement")) {
                            hook("count", "jdbc_statement_creates");
                        }
                        if (type.endsWith("/CompiledSelector") && (
                                owner.equals("java/util/function/Predicate") && name.equals("test")
                                || owner.endsWith("/OperationCatalog$PredicateBinding") && name.equals("matches"))) {
                            hook("count", "predicate_calls");
                        }
                        boolean derivedClassification = type.endsWith("/IocProcessingOperations")
                                && (owner.endsWith("/IndicatorClassifier") && name.equals("classify")
                                || owner.endsWith("/IndicatorProcessingSession") && name.equals("classifyWith"));
                        if (derivedClassification) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "derivedStart", "()V", false);
                        }
                        if ((type.endsWith("/InvocationViews") || type.endsWith("/CamelRouteRuntime"))
                                && owner.equals("org/apache/camel/ProducerTemplate") && name.startsWith("request")) {
                            hook("count", "template_sends");
                            if (desc.startsWith("(Ljava/lang/String;")) {
                                hook("count", "string_template_sends");
                            }
                            if (type.endsWith("/InvocationViews")) {
                                hook("count", "view_operation_sends");
                            }
                        }
                        if (type.endsWith("/IocProcessingOperations") && owner.equals("java/lang/String")
                                && name.equals("split")) {
                            hook("count", "argument_splits");
                        }
                        if (type.endsWith("/PslHostClassifier") && owner.equals("com/google/common/net/InternetDomainName")
                                && name.equals("from")) {
                            hook("count", "psl_domain_parses");
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                        if (admission && name.equals("enter")) {
                            hook("finish", "writer_admission_wait_nanos");
                            hook("start", "writer_admission_hold_nanos");
                            hook("count", "writer_admissions");
                        }
                        if (ownership) {
                            hook("finish", "canonical_writer_wait_nanos");
                            hook("start", "canonical_writer_hold_nanos");
                            hook("count", "canonical_transactions");
                        }
                        if (canonicalWrite && owner.equals("java/sql/Connection") && name.equals("commit")) {
                            hook("finish", "canonical_writer_hold_nanos");
                        }
                        if (derivedClassification) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "derivedFinish", "()V", false);
                        }
                        if (type.endsWith("/ArtifactOccurrenceSelector$Accumulator") && method.equals("add")
                                && owner.equals("java/util/Map") && name.equals("put")) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, type, "winners", "Ljava/util/Map;");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER,
                                    "retainedWinners", "(Ljava/util/Map;)V", false);
                        }
                        if (type.endsWith("/InvocationViews") && owner.equals("java/util/Map")
                                && name.equals("put") && (method.equals("<init>") || method.equals("resolve"))) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, type, "resolved", "Ljava/util/Map;");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER,
                                    "cached", "(Ljava/util/Map;)V", false);
                        }
                    }

                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            if (documentPreparation || reservation || importReturn) {
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER,
                                        reservation ? "reserved" : importReturn ? "imported" : "prepared",
                                        "(Ljava/lang/Object;)V", false);
                            }
                            if (timer != null) {
                                hook("finish", timer);
                            }
                        }
                        super.visitInsn(opcode);
                    }

                    private void hook(String name, String value) {
                        super.visitLdcInsn(value);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, name, "(Ljava/lang/String;)V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
