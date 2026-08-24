package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportDrainCoordinator;
import com.iocextractor.application.dataframeimport.ImportDeliverySnapshotPinned;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/** Converts a lossy post-snapshot event into a coalesced global-lane nudge. */
final class DataframeImportSnapshotPinnedListener {

    private final DataframeImportDrainCoordinator drain;

    DataframeImportSnapshotPinnedListener(DataframeImportDrainCoordinator drain) {
        this.drain = Objects.requireNonNull(drain, "drain");
    }

    @EventListener
    void onSnapshotPinned(ImportDeliverySnapshotPinned ignored) {
        drain.nudge();
    }
}
