package com.iocextractor.adapter.out.sink.csv;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CountingCharsetWriterTest {

    private static final Charset CP1251 = Charset.forName("windows-1251");

    @Test
    void counts_chunks_with_characters_unrepresentable_in_target_charset() throws Exception {
        var counting = new CountingCharsetWriter(new StringWriter(), CP1251);

        counting.write("ФСТЭК");   // representable in cp1251
        counting.write("emoji 😀"); // not representable
        counting.close();

        assertThat(counting.unmappable()).isEqualTo(1);
    }

    @Test
    void reports_zero_for_fully_representable_output() throws Exception {
        var counting = new CountingCharsetWriter(new StringWriter(), StandardCharsets.UTF_8);

        counting.write("anything 😀 ФСТЭК");
        counting.close();

        assertThat(counting.unmappable()).isZero();
    }
}
