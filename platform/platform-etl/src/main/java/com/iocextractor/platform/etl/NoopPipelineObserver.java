package com.iocextractor.platform.etl;

/**
 * Pipeline observer that intentionally does nothing.
 */
public final class NoopPipelineObserver implements PipelineObserver {

    private static final AutoCloseable NOOP_SCOPE = () -> {
    };

    @Override
    public AutoCloseable openStage(EnvelopeMeta meta) {
        return NOOP_SCOPE;
    }

    @Override
    public void stageStarted(EnvelopeMeta meta) {
    }

    @Override
    public void stageCompleted(EnvelopeMeta meta, long durationNanos) {
    }

    @Override
    public void stageFailed(EnvelopeMeta meta, long durationNanos, RuntimeException failure) {
    }
}
