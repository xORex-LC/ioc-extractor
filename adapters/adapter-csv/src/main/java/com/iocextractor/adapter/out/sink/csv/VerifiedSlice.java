package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.SliceManifest;

/** Result of verifying the complete immutable slice integrity chain. */
record VerifiedSlice(String manifestSha256, SliceManifest manifest, boolean successPresent) {
}
