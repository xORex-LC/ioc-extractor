package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.SliceManifest;

/** Serialization boundary for the versioned slice manifest format. */
public interface SliceManifestCodec {

    byte[] encode(SliceManifest manifest);

    SliceManifest decode(byte[] bytes);
}
