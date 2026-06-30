package com.iocextractor.application.export;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.util.Objects;

/** Control-plane fact emitted after an export slice reaches durable completed state. */
public record SliceCompleted(ControlEventMetadata metadata,
                             String profile,
                             String sliceId,
                             String sliceName,
                             String manifestSha256) implements ControlEvent {

    public static final String EVENT_TYPE = "export.slice.completed";
    public static final int EVENT_VERSION = 1;

    public SliceCompleted {
        metadata = Objects.requireNonNull(metadata, "metadata");
        profile = ExportArtifactSpec.requireText(profile, "profile");
        sliceId = ExportArtifactSpec.requireText(sliceId, "sliceId");
        sliceName = ExportArtifactSpec.requireText(sliceName, "sliceName");
        manifestSha256 = ExportArtifactSpec.requireSha256(manifestSha256, "manifestSha256");
    }

    /** Creates the event from a terminal completed export run. */
    public static SliceCompleted from(ExportRun run) {
        Objects.requireNonNull(run, "run");
        if (run.status() != ExportRunStatus.COMPLETED) {
            throw new IllegalArgumentException("SliceCompleted requires a COMPLETED export run");
        }
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "slice-completed:" + run.runId(),
                EVENT_TYPE,
                EVENT_VERSION,
                run.updatedAt(),
                run.runId());
        return new SliceCompleted(metadata, run.profile(), run.runId(), run.sliceName(), run.manifestSha256());
    }
}
