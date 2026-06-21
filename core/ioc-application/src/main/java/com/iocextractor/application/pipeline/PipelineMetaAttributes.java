package com.iocextractor.application.pipeline;

/**
 * Application-owned metadata keys attached to the generic ETL envelope.
 */
public final class PipelineMetaAttributes {

    /** Source path used for operational logging and diagnostics context. */
    public static final String SOURCE_PATH = "ioc.source.path";

    /** Dry-run flag used by write stages to suppress side effects. */
    public static final String DRY_RUN = "ioc.dry_run";

    /** Observability mode value, such as {@code oneshot} or {@code daemon}. */
    public static final String MODE = "ioc.mode";

    private PipelineMetaAttributes() {
    }
}
