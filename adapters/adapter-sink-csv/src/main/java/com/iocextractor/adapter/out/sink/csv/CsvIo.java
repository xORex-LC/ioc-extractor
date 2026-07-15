package com.iocextractor.adapter.out.sink.csv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Charset-aware CSV I/O helpers shared by the CSV writers/readers in this module.
 *
 * <p>The output charset is declared in {@code ioc.sink.csv.charset}. Some target
 * charsets (e.g. {@code windows-1251}) cannot represent every Unicode character;
 * to keep a single bad value from failing the whole artifact, the writer replaces
 * unmappable characters rather than throwing. For UTF-8 (the default) this is a
 * no-op, since UTF-8 maps every character.
 */
final class CsvIo {

    private CsvIo() {
    }

    /** Buffered writer in {@code charset}, replacing unmappable/malformed characters. */
    static BufferedWriter newWriter(Path path, Charset charset) throws IOException {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(path), encoder));
    }

    /** Buffered reader in {@code charset}, replacing malformed/unmappable input. */
    static Reader newReader(Path path, Charset charset) throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder));
    }
}
