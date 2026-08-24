package com.iocextractor.application.dataframeimport;

/** Application seam that produces one sealed, promotion-ready import stage. */
@FunctionalInterface
public interface DataframeImportStager {

    /** Recognizes, maps and seals one immutable delivery snapshot. */
    ImportStagingResult stage(ImportStagingCommand command);
}
