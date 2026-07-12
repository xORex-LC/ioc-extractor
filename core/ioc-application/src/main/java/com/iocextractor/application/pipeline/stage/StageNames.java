package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.StageId;

/**
 * Stable {@link StageId} constants for the IOC extraction pipeline.
 *
 * <p>These are identifiers only; stage order is owned by the pipeline assembly,
 * not by the stages. Renaming a value after publication breaks the
 * {@code ioc.stage} observability contract.
 */
public final class StageNames {

    /** Source document reading. */
    public static final StageId READ_SOURCE = new StageId("READ_SOURCE");

    /** Defanged text restoration. */
    public static final StageId REFANG = new StageId("REFANG");

    /** Indicator extraction. */
    public static final StageId EXTRACT = new StageId("EXTRACT");

    /** Source attribution. */
    public static final StageId ATTRIBUTE = new StageId("ATTRIBUTE");

    /** Feature extraction and mask classification. */
    public static final StageId CLASSIFY = new StageId("CLASSIFY");

    /** De-duplication. */
    public static final StageId DEDUPLICATE = new StageId("DEDUPLICATE");

    /** Side-effect-free artifact filtering and row mapping. */
    public static final StageId PREPARE_ARTIFACTS = new StageId("PREPARE_ARTIFACTS");

    /** Artifact writing. */
    public static final StageId WRITE_ARTIFACTS = new StageId("WRITE_ARTIFACTS");

    private StageNames() {
    }
}
