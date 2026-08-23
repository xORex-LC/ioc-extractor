package com.iocextractor.adapter.out.sink.csv;

import java.io.IOException;
import java.nio.file.Path;

/** Filesystem durability seam used by immutable slice publication. */
interface SliceFileOperations {

    void forceFile(Path file) throws IOException;

    void forceDirectory(Path directory) throws IOException;

    void moveAtomically(Path source, Path target) throws IOException;
}
