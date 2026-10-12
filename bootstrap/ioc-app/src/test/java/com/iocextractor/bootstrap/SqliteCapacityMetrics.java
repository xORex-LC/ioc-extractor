package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleClock;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import com.iocextractor.adapter.out.store.jdbc.StagedCapacityBundle;
import com.iocextractor.application.port.in.artifact.lifecycle.RunLifecycleHistoryRetentionUseCase;
import com.sun.management.ThreadMXBean;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only exact admission-window observer. Same bounded instrumentation in both qualification variants. */
public final class SqliteCapacityMetrics {
    private static final String ADMISSION = "com/iocextractor/adapter/out/store/jdbc/JdbcWriterAdmission";
    private static final String HOOK = "com/iocextractor/bootstrap/SqliteCapacityMetrics";
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static final Map<String, JdbcWriterAdmission.Metrics> OPERATIONS =
            new TreeMap<>();
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile boolean installed;
    private static volatile boolean active;
    private static volatile Object canonicalAdmission;
    private static long started;
    private static long allocatedBefore;

    private SqliteCapacityMetrics() { }

    /** Hooks only the already-computed wait/hold values, including failed transactions' finally path. */
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                    ProtectionDomain protection, byte[] bytes) {
                if (!ADMISSION.equals(name)) { return null; }
                try { return instrument(bytes);
                } catch (Throwable failure) {
                    FAILURE.compareAndSet(null, failure);
                    return null;
                }
            }
        });
    }

    static byte[] instrument(byte[] bytes) {
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!method.equals("leave") || !descriptor.equals("(L" + ADMISSION + "$Request;J)V")) { return next; }
                installed = true;
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, ADMISSION + "$Request", "operation",
                                    "()L" + ADMISSION + "$OperationClass;", false);
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
                            visitVarInsn(Opcodes.LLOAD, 6); // existing wait local
                            visitVarInsn(Opcodes.LLOAD, 4); // existing hold local
                            visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "completed", "(Ljava/lang/Object;Ljava/lang/String;JJ)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    /** Admission callbacks own no payload and never throw into a completed business operation. */
    public static synchronized void completed(Object admission, String operation, long wait, long hold) {
        if (!active || admission != canonicalAdmission) { return; }
        if (wait < 0 || hold < 0) {
            FAILURE.compareAndSet(null, new IllegalStateException("Invalid admission timing locals"));
            return;
        }
        var previous = OPERATIONS.getOrDefault(operation, new JdbcWriterAdmission.Metrics(0, 0, 0, 0, 0));
        OPERATIONS.put(operation, new JdbcWriterAdmission.Metrics(previous.completed() + 1,
                previous.totalWaitNanos() + wait, Math.max(previous.maximumWaitNanos(), wait),
                previous.totalHoldNanos() + hold, Math.max(previous.maximumHoldNanos(), hold)));
    }

    static synchronized void observeAdmission(Object admission) {
        if (active) { throw new IllegalStateException("Cannot change admission identity inside a window"); }
        canonicalAdmission = java.util.Objects.requireNonNull(admission, "admission");
    }

    static synchronized Map<String, Object> begin() {
        requireValid();
        if (active) { throw new IllegalStateException("Qualification window is already active"); }
        if (!THREADS.isThreadAllocatedMemorySupported()) { throw new IllegalStateException("Allocation accounting unavailable"); }
        THREADS.setThreadAllocatedMemoryEnabled(true);
        OPERATIONS.clear();
        allocatedBefore = THREADS.getTotalThreadAllocatedBytes();
        if (allocatedBefore < 0) { throw new IllegalStateException("Total process allocation accounting unavailable"); }
        started = System.nanoTime(); active = true;
        return snapshot();
    }

    static synchronized Map<String, Object> snapshot() {
        requireValid();
        return Map.of("active", active, "elapsedNanos", System.nanoTime() - started,
                "allocatedProcessBytes", THREADS.getTotalThreadAllocatedBytes() - allocatedBefore,
                "writerOperations", Map.copyOf(OPERATIONS), "admissionWindowMaximaExact", true,
                "admissionScope", "canonical_bean_only",
                "heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }

    static synchronized Map<String, Object> end() {
        if (!active) { throw new IllegalStateException("Qualification window is inactive"); }
        var result = snapshot(); active = false;
        return result;
    }

    private static void requireValid() {
        if (!installed || canonicalAdmission == null || FAILURE.get() != null) {
            throw new IllegalStateException("Admission instrumentation absent or failed", FAILURE.get());
        }
    }

    /** Loopback-only qualification controls, absent from the application JAR. */
    @RestController
    @ConditionalOnProperty(name = "qualification.sqlite")
    public static class Endpoints {
        private final JdbcWriterAdmission writers;
        private final JdbcLifecycleClock clock;
        private final RunLifecycleHistoryRetentionUseCase retention;
        private final ObjectProvider<StagedCapacityBundle> staged;
        private final com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore projections;
        private final javax.sql.DataSource dataframe;
        private final com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore receipts;

        public Endpoints(JdbcWriterAdmission writers, JdbcLifecycleClock clock,
                RunLifecycleHistoryRetentionUseCase retention, ObjectProvider<StagedCapacityBundle> staged,
                com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore projections,
                @org.springframework.beans.factory.annotation.Qualifier("dataframeStorageDataSource") javax.sql.DataSource dataframe,
                com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore receipts) {
            this.writers = writers; this.clock = clock; this.retention = retention; this.staged = staged;
            this.projections = projections;
            this.dataframe = dataframe;
            this.receipts = receipts;
            observeAdmission(writers);
        }
        @PostMapping("/qualification/window/start") public Map<String, Object> start() {
            var candidate = staged.getIfAvailable();
            if (candidate != null) { candidate.beginWindow(); }
            return begin();
        }
        @GetMapping("/qualification/window") public Map<String, Object> window() { return snapshot(); }
        @PostMapping("/qualification/receipt-families") public Map<String, Object> receiptFamilies() throws java.sql.SQLException {
            return ReceiptPayloadDiagnostics.inspect(dataframe);
        }
        @PostMapping("/qualification/receipt-read-burst") public Map<String, Object> receiptReadBurst(
                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int count) throws Exception {
            return ReceiptPayloadDiagnostics.read(dataframe, receipts, clock.now(), count);
        }
        @PostMapping("/qualification/window/end") public Map<String, Object> finish() {
            var result = new java.util.LinkedHashMap<>(end());
            var candidate = staged.getIfAvailable();
            result.put("publicationPhases", candidate == null ? Map.of() : candidate.endWindow());
            return result;
        }
        @PostMapping("/qualification/control") public Map<String, Object> control() {
            long eligible = System.nanoTime();
            long rawBefore = System.currentTimeMillis();
            var value = writers.execute(JdbcWriterAdmission.OperationClass.CONTROL, clock::now);
            return Map.of("eligibleToCompleteNanos", System.nanoTime() - eligible, "effectiveUTC", value.value().toString(),
                    "utcBeforeEpochMs", rawBefore, "utcAfterEpochMs", System.currentTimeMillis(),
                    "eligibleMonotonicNanos", eligible, "completedMonotonicNanos", System.nanoTime(),
                    "clockSnapshotAfter", clock.inspect());
        }

        /** Executes the real projection port, including a generation-scoped error when work is pending. */
        @PostMapping("/qualification/ack") public Map<String, Object> acknowledge(
                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean pendingOnly) {
            long eligible = System.nanoTime();
            long rawBefore = System.currentTimeMillis();
            long readStarted = System.nanoTime();
            var before = projections.load("masks");
            long readNanos = System.nanoTime() - readStarted;
            boolean failureApplied = (pendingOnly || before.pending()) && projections.recordFailure("masks", before.requiredGeneration(),
                    "QUALIFICATION.PROJECTION_FAILED");
            boolean shouldAcknowledge = !pendingOnly || before.pending();
            boolean applied = shouldAcknowledge && projections.acknowledge(
                    new com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement("masks", before.requiredGeneration()));
            var after = projections.load("masks");
            if (shouldAcknowledge && (!applied || after.projectedGeneration().compareTo(before.requiredGeneration()) < 0)) {
                throw new IllegalStateException("Projection acknowledgement did not preserve its admitted generation");
            }
            return Map.of("eligibleToCompleteNanos", System.nanoTime() - eligible,
                    "readNanos", readNanos, "failureApplied", failureApplied, "ackApplied", applied,
                    "requiredGeneration", after.requiredGeneration().value(),
                    "projectedGeneration", after.projectedGeneration().value(),
                    "utcBeforeEpochMs", rawBefore, "utcAfterEpochMs", System.currentTimeMillis(),
                    "eligibleMonotonicNanos", eligible, "completedMonotonicNanos", System.nanoTime());
        }

        /** Read-only post-publication samples, with no ACK mutations or artificial bulk warmup. */
        @PostMapping("/qualification/projection-read-burst") public Map<String, Object> readBurst(
                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int count) {
            if (count < 1 || count > 500) { throw new IllegalArgumentException("Read count must be 1..500"); }
            var samples = new java.util.ArrayList<Long>(count);
            for (int index = 0; index < count; index++) {
                long before = System.nanoTime();
                projections.load("masks");
                samples.add(System.nanoTime() - before);
            }
            return Map.of("samplesNanos", samples, "scope", "committed masks projection metadata through the real port");
        }

        /** Separate command identities: this probe never writes an error as part of ACK. */
        @PostMapping("/qualification/pure-ack") public Map<String, Object> pureAcknowledgement() {
            long started = System.nanoTime();
            var before = projections.load("masks");
            boolean applied = projections.acknowledge(new com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement(
                    "masks", before.requiredGeneration()));
            var after = projections.load("masks");
            if (!applied || after.projectedGeneration().compareTo(before.requiredGeneration()) < 0) {
                throw new IllegalStateException("Pure ACK lost its admitted generation");
            }
            return Map.of("kind", "pure-ack", "eligibleToCompleteNanos", System.nanoTime() - started,
                    "ackApplied", applied, "requiredGeneration", after.requiredGeneration().value(),
                    "projectedGeneration", after.projectedGeneration().value());
        }

        /** Error persistence is still synchronous and durable; acknowledgement semantics stay unchanged. */
        @PostMapping("/qualification/pure-error") public Map<String, Object> pureError() {
            long started = System.nanoTime();
            var before = projections.load("masks");
            boolean applied = projections.recordFailure("masks", before.requiredGeneration(), "QUALIFICATION.PURE_ERROR");
            return Map.of("kind", "pure-error", "eligibleToCompleteNanos", System.nanoTime() - started,
                    "failureApplied", applied, "pendingAtRead", before.pending());
        }

        /** Bounded retained samples; sampler failures propagate synchronously and invalidate the window. */
        @PostMapping("/qualification/ack-burst") public Map<String, Object> burst(
                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "200") int count)
                throws java.io.IOException, InterruptedException {
            if (count < 1 || count > 500) { throw new IllegalArgumentException("ACK burst must contain 1..500 operations"); }
            var samples = new java.util.ArrayList<Map<String, Object>>(count);
            long heap = 0;
            long rss = 0;
            for (int index = 0; index < count; index++) {
                // Same one-millisecond cadence in both variants: avoid accidental same-timestamp no-ops.
                Thread.sleep(1);
                samples.add(acknowledge(false));
                heap = Math.max(heap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                try (var status = java.nio.file.Files.lines(java.nio.file.Path.of("/proc/self/status"))) {
                    String line = status.filter(value -> value.startsWith("VmRSS:")).findFirst()
                            .orElseThrow(() -> new java.io.IOException("Missing current RSS"));
                    rss = Math.max(rss, Long.parseLong(line.split("\\s+")[1]) * 1024L);
                }
            }
            return Map.of("samples", samples, "sampledHeapMaximumBytes", heap, "sampledRssMaximumBytes", rss,
                    "memorySampling", "after each completed ACK; not a continuous peak");
        }

        @PostMapping("/qualification/projection-evidence") public Map<String, Object> projectionEvidence() throws java.sql.SQLException {
            return staged.getObject().projectionEvidence();
        }
        @PostMapping("/qualification/maintenance-evidence") public Map<String, Object> maintenanceEvidence() throws java.sql.SQLException {
            return staged.getObject().cleanupEvidence();
        }
        @PostMapping("/qualification/maintenance") public Map<String, Object> cleanup() {
            long eligible = System.nanoTime();
            long rawBefore = System.currentTimeMillis();
            var candidate = staged.getIfAvailable();
            retention.run();
            String page = candidate == null ? "native retained facts" : candidate.maintenance();
            return Map.of("eligibleToCompleteNanos", System.nanoTime() - eligible, "versionPage", page,
                    "utcBeforeEpochMs", rawBefore, "utcAfterEpochMs", System.currentTimeMillis(),
                    "eligibleMonotonicNanos", eligible, "completedMonotonicNanos", System.nanoTime(),
                    "clockSnapshotAfter", clock.inspect());
        }

        /** Test-only failure context; the collector retains HTTP failure and rejects the entire window. */
        @org.springframework.web.bind.annotation.ExceptionHandler(RuntimeException.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> failure(RuntimeException failure) {
            var context = new java.util.LinkedHashMap<String, Object>();
            context.put("error", failure.getClass().getName()); context.put("message", failure.getMessage());
            context.put("utcEpochMs", System.currentTimeMillis()); context.put("monotonicNanos", System.nanoTime());
            try { context.put("clockSnapshotAfter", clock.inspect()); }
            catch (RuntimeException inspection) { context.put("inspectionError", inspection.toString()); }
            return org.springframework.http.ResponseEntity.internalServerError().body(context);
        }
    }
}
