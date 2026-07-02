package com.iocextractor.bootstrap;

import com.iocextractor.application.sync.RemoteFetchSource;

/** Driving seam used by periodic and push paths to request source detection. */
interface RemoteFetchDetectionTrigger {

    /** Requests detection for one source. */
    void trigger(RemoteFetchSource source, RemoteFetchDetectionReason reason);
}
