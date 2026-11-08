package com.iocextractor.platform.etl;

/**
 * Synchronous observation and scope hooks for a pipeline run.
 *
 * <p>Callbacks execute on the runner's calling thread. Implementations shared
 * by concurrent runs must protect their own state and should not throw. The
 * runner does not generally isolate observers: an opening/started failure can
 * prevent execution, and completion/scope-close failure can fail a call after
 * stage effects have occurred. Observation is not a transaction or durable
 * completion record. Scope handles use try-with-resources cleanup: a secondary
 * close failure is suppressed when another failure is already propagating.
 *
 * <p>{@link #stageFailed(EnvelopeMeta, long, RuntimeException)} is not an exhaustive
 * failure notification. Run/stage opening, stage-start and scope-close failures,
 * and {@link Error}s need not produce it. A runtime failure from that callback
 * is suppressed onto the primary runtime failure when distinct; its {@code Error}
 * is not contained.
 */
public interface PipelineObserver {

    /**
     * Opens one pipeline-run scope. Implementations may expose stable
     * correlation metadata through MDC or another thread-local bridge. The
     * runner closes the returned handle when this invocation unwinds. A complete
     * invocation normally delivers its terminal diagnostic summary before closing;
     * a successful preparation segment defers that summary to its owner.
     *
     * @param meta initial run metadata
     * @return closeable run scope
     */
    default AutoCloseable openRun(EnvelopeMeta meta) {
        return () -> {
        };
    }

    /**
     * Opens stage execution scope. Implementations may use it for MDC or
     * similar thread-local context. After a successful open, the runner attempts
     * to close the returned handle while unwinding, including on stage failure.
     * A primary checked close failure is wrapped in {@link StageExecutionException};
     * a primary unchecked close failure propagates unchanged.
     *
     * @param meta stage metadata
     * @return closeable stage scope
     */
    AutoCloseable openStage(EnvelopeMeta meta);

    /**
     * Called after stage-scope opening and before stage execution. A failure here
     * propagates without a paired {@code stageFailed} callback.
     *
     * @param meta stage metadata
     */
    void stageStarted(EnvelopeMeta meta);

    /**
     * Called after stage execution, diagnostic delivery and accepted policy
     * evaluation. A runtime failure here can propagate after the stage's effects
     * and is itself reported through {@code stageFailed}.
     *
     * @param meta stage metadata
     * @param durationNanos elapsed nanoseconds since stage-start observation returned,
     *                      including diagnostic delivery and policy evaluation
     */
    void stageCompleted(EnvelopeMeta meta, long durationNanos);

    /**
     * Called for a runtime failure in the stage body, diagnostic processing,
     * policy evaluation or completion callback; not for every failure of a run.
     *
     * @param meta stage metadata
     * @param durationNanos elapsed nanoseconds since stage-start observation returned
     * @param failure primary runtime failure to propagate
     */
    void stageFailed(EnvelopeMeta meta, long durationNanos, RuntimeException failure);
}
