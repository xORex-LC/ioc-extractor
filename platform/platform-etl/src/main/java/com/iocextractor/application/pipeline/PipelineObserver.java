package com.iocextractor.application.pipeline;

/**
 * Observer port for pipeline operational events.
 */
public interface PipelineObserver {

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
