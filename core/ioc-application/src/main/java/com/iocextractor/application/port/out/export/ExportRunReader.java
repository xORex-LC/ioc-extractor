package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;

import java.util.Optional;

/** Read model for operational status of durable export runs. */
public interface ExportRunReader {

    /** Returns the most recently updated run for one profile/status pair. */
    Optional<ExportRun> findLatest(String profile, ExportRunStatus status);
}
