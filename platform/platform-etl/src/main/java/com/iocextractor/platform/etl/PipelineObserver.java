package com.iocextractor.platform.etl;

/**
 * Observer port for pipeline operational events.
 */
public interface PipelineObserver {

    /**
     * Opens one pipeline-run scope. Implementations may expose stable
     * correlation metadata through MDC or another thread-local bridge. The
     * returned scope is closed after terminal diagnostic delivery.
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
     * similar thread-local context. The returned scope is always closed by the
     * runner.
     *
     * @param meta stage metadata
     * @return closeable stage scope
     */
    AutoCloseable openStage(EnvelopeMeta meta);

    /**
     * Called before stage execution.
     *
     * @param meta stage metadata
     */
    void stageStarted(EnvelopeMeta meta);

    /**
     * Called after successful stage execution and failure policy evaluation.
     *
     * @param meta stage metadata
     * @param durationNanos stage duration in nanoseconds
     */
    void stageCompleted(EnvelopeMeta meta, long durationNanos);

    /**
     * Called when a stage or failure policy rejects execution.
     *
     * @param meta stage metadata
     * @param durationNanos stage duration in nanoseconds
     * @param failure failure
     */
    void stageFailed(EnvelopeMeta meta, long durationNanos, RuntimeException failure);
}
