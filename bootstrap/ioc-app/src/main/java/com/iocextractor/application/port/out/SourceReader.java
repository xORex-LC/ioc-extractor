package com.iocextractor.application.port.out;

import java.nio.file.Path;

/**
 * Secondary (driven) port: extract plain text from a source document,
 * regardless of its format. Implemented by adapters (Tika, jsoup, …).
 */
public interface SourceReader {

    String readText(Path source);
}
