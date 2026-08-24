package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;

/** Protected terminal-unit access used only to create a causally linked replay. */
public interface ImportReplaySnapshotStore {

    /** Materializes a new immutable snapshot from one retained terminal unit. */
    ImportSnapshot materializeReplay(ImportDeliveryId terminalDeliveryId,
                                     ImportDeliveryId replayDeliveryId);
}
