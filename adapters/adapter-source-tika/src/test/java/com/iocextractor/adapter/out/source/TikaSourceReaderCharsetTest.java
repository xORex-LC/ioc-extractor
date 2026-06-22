package com.iocextractor.adapter.out.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TikaSourceReaderCharsetTest {

    @TempDir
    Path tempDir;

    private static final Charset CP1251 = Charset.forName("windows-1251");

    @Test
    void forced_charset_decodes_legacy_cp1251_html() throws Exception {
        // HTML without a meta charset, bytes encoded in cp1251 — the case auto-detect gets wrong.
        Path html = tempDir.resolve("legacy.htm");
        Files.write(html, "<html><body>Письмо ФСТЭК России</body></html>".getBytes(CP1251));

        String forced = new TikaSourceReader(CP1251).readText(html);

        assertThat(forced).contains("ФСТЭК");
    }

    @Test
    void wrong_forced_charset_does_not_yield_the_cyrillic_text() throws Exception {
        Path html = tempDir.resolve("legacy2.htm");
        Files.write(html, "<html><body>Письмо ФСТЭК России</body></html>".getBytes(CP1251));

        // Forcing US-ASCII proves the knob takes effect: cp1251 high bytes cannot decode to Cyrillic.
        String wrong = new TikaSourceReader(StandardCharsets.US_ASCII).readText(html);

        assertThat(wrong).doesNotContain("ФСТЭК");
    }
}
