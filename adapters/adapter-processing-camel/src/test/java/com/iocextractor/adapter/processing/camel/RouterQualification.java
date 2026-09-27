package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Opt-in, synthetic Router qualification. This is not an IOC preparation baseline. */
public final class RouterQualification {
    private static final FailureReference NO_HOST = new FailureReference("host", "NO_HOST");
    private static final int WARMUP = 2_000;

    private RouterQualification() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: RouterQualification SIZE BRANCHES CALLERS MODE");
        }
        int size = Integer.parseInt(args[0]);
        int branches = Integer.parseInt(args[1]);
        int callers = Integer.parseInt(args[2]);
        Mode mode = Mode.valueOf(args[3].toUpperCase(java.util.Locale.ROOT));
        Measurement result = measure(size, branches, callers, mode);
        System.out.println("size,branches,callers,mode,compile_ms,start_ms,run_ms,rows_per_second,"
                + "allocated_bytes_per_input,started_heap_bytes,retained_growth_bytes,prepared,blocked,recovered");
        System.out.println(result.csv());
    }

    /** Runs one deterministic profile, including a warmup excluded from reported execution cost. */
    public static Measurement measure(int size, int branches, int callers, Mode mode) throws Exception {
        if (size < 1 || !Set.of(1, 4, 16).contains(branches) || !Set.of(1, 4).contains(callers)) {
            throw new IllegalArgumentException("size must be positive; branches=1/4/16; callers=1/4");
        }
        long baselineHeap = usedHeapAfterGc();
        long compileStart = System.nanoTime();
        var compiled = new CamelPlanCompiler().compile(List.of(plan(branches, mode)), catalog());
        long compileNanos = System.nanoTime() - compileStart;
        long start = System.nanoTime();
        try (var runtime = new CamelRouteRuntime(compiled)) {
            long startupNanos = System.nanoTime() - start;
            long startedHeap = usedHeapAfterGc() - baselineHeap;
            runInputs(runtime, WARMUP, callers, mode, branches, false);
            long runStart = System.nanoTime();
            Totals totals = runInputs(runtime, size, callers, mode, branches, true);
            long elapsed = System.nanoTime() - runStart;
            long retainedGrowth = usedHeapAfterGc() - baselineHeap - startedHeap;
            return new Measurement(size, branches, callers, mode, compileNanos, startupNanos,
                    elapsed, totals.allocatedBytes(), startedHeap, retainedGrowth,
                    totals.prepared(), totals.blocked(), totals.recovered());
        }
    }

    private static Totals runInputs(CamelRouteRuntime runtime, int size, int callers, Mode mode,
                                     int branches, boolean measureAllocations) throws Exception {
        var executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Totals>> futures = new ArrayList<>();
            for (int worker = 0; worker < callers; worker++) {
                final int first = worker;
                futures.add(executor.submit((Callable<Totals>) () -> {
                    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
                    boolean supportsAllocations = bean.isThreadAllocatedMemorySupported();
                    if (measureAllocations && supportsAllocations && !bean.isThreadAllocatedMemoryEnabled()) {
                        bean.setThreadAllocatedMemoryEnabled(true);
                    }
                    long before = measureAllocations && supportsAllocations
                            ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
                    long prepared = 0;
                    long blocked = 0;
                    long recovered = 0;
                    for (int index = first; index < size; index += callers) {
                        var result = runtime.execute("qualification", new Input(index, mode));
                        boolean fails = mode != Mode.SUCCESS && index % 4 == 0;
                        int expectedReplies = mode == Mode.FAILURE && fails ? 0 : branches;
                        if (result.replies().size() != expectedReplies) {
                            throw new IllegalStateException("unexpected reply count for input " + index);
                        }
                        for (PlanExecutionResult.BranchReply reply : result.replies()) {
                            if (!(reply.outcome() instanceof BranchOutcome.Prepared candidate)
                                    || !Integer.valueOf(index).equals(candidate.candidate())) {
                                throw new IllegalStateException("wrong branch candidate for input " + index);
                            }
                        }
                        if (fails && mode == Mode.FAILURE) {
                            if (result.preparationBlocked().size() != branches || result.failures().size() != 1) {
                                throw new IllegalStateException("lost blocked failure for input " + index);
                            }
                            blocked++;
                        } else if (fails && mode == Mode.RECOVERY) {
                            if (result.recoveryAttempts().size() != 1 || result.failures().size() != 1) {
                                throw new IllegalStateException("lost recovery evidence for input " + index);
                            }
                            recovered++;
                        } else {
                            if (!result.failures().isEmpty() || !result.recoveryAttempts().isEmpty()) {
                                throw new IllegalStateException("spurious failure for input " + index);
                            }
                        }
                        prepared += expectedReplies;
                    }
                    long allocated = before < 0 ? -1
                            : bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                    return new Totals(prepared, blocked, recovered, allocated);
                }));
            }
            Totals sum = new Totals(0, 0, 0, 0);
            for (Future<Totals> future : futures) {
                Totals part = future.get(120, TimeUnit.SECONDS);
                sum = new Totals(sum.prepared() + part.prepared(), sum.blocked() + part.blocked(),
                        sum.recovered() + part.recovered(),
                        sum.allocatedBytes() < 0 || part.allocatedBytes() < 0 ? -1
                                : sum.allocatedBytes() + part.allocatedBytes());
            }
            return sum;
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("qualification workers did not terminate");
            }
        }
    }

    private static PlanDescriptor plan(int branches, Mode mode) {
        List<PlanDescriptor.View> views = new ArrayList<>();
        views.add(new PlanDescriptor.View("host", "host", "original"));
        String required = "host";
        if (mode == Mode.RECOVERY) {
            views.add(new PlanDescriptor.View("alternate", "alternate", "original"));
            views.add(new PlanDescriptor.View("usable", "view.recover", "host",
                    new PlanDescriptor.Recovery("alternate", Set.of("NO_HOST"))));
            required = "usable";
        }
        List<PlanDescriptor.Branch> destinations = new ArrayList<>();
        for (int i = 0; i < branches; i++) {
            destinations.add(new PlanDescriptor.Branch("branch" + i, "capture", null,
                    List.of(required)));
        }
        return new PlanDescriptor("qualification", views, new PlanDescriptor.Routing(
                PlanDescriptor.Mode.ALL, destinations,
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
    }

    private static OperationCatalog catalog() {
        return new OperationCatalog(Map.of(
                "host", exchange -> {
                    Input input = exchange.getMessage().getBody(Input.class);
                    exchange.getMessage().setBody(input.mode() != Mode.SUCCESS && input.index() % 4 == 0
                            ? new ViewOutcome.Unavailable(NO_HOST)
                            : new ViewOutcome.Available(input.index()));
                },
                "alternate", exchange -> exchange.getMessage().setBody(new ViewOutcome.Available(
                        exchange.getMessage().getBody(Input.class).index()))),
                Map.of("capture", exchange -> {
                    var input = exchange.getMessage().getBody(PlanExecutionResult.BranchInput.class);
                    exchange.getMessage().setBody(new BranchOutcome.Prepared(
                            ((Input) input.original()).index()));
                }), Map.of(), Set.of("NO_HOST"));
    }

    private static long usedHeapAfterGc() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public enum Mode { SUCCESS, FAILURE, RECOVERY }

    private record Input(int index, Mode mode) { }

    private record Totals(long prepared, long blocked, long recovered, long allocatedBytes) { }

    public record Measurement(int size, int branches, int callers, Mode mode, long compileNanos,
                              long startupNanos, long elapsedNanos, long allocatedBytes,
                              long startedHeapBytes, long retainedGrowthBytes, long prepared,
                              long blocked, long recovered) {
        public String csv() {
            double elapsedSeconds = Duration.ofNanos(elapsedNanos).toNanos() / 1_000_000_000d;
            return size + "," + branches + "," + callers + "," + mode + ","
                    + compileNanos / 1_000_000d + "," + startupNanos / 1_000_000d + ","
                    + elapsedNanos / 1_000_000d + "," + size / elapsedSeconds + ","
                    + (allocatedBytes < 0 ? -1 : (double) allocatedBytes / size) + ","
                    + startedHeapBytes + "," + retainedGrowthBytes + ","
                    + prepared + "," + blocked + "," + recovered;
        }
    }
}
