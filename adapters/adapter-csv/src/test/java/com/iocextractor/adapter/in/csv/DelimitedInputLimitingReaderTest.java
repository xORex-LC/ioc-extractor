package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelimitedInputLimitingReaderTest {

    private static final DelimitedDialect DIALECT = new DelimitedDialect(
            ';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of());

    @Test
    void counts_delimiters_inside_quotes_as_field_content() throws Exception {
        assertThat(read("\"12;3\"\n", limits(4, 16))).isEqualTo("\"12;3\"\n");

        assertThatThrownBy(() -> read("\"12;34\"\n", limits(4, 16)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("field limit");
    }

    @Test
    void counts_escaped_quotes_as_one_decoded_field_character() throws Exception {
        assertThat(read("\"12\"\"3\"\n", limits(4, 16))).isEqualTo("\"12\"\"3\"\n");
    }

    @Test
    void does_not_reset_field_limit_at_a_quoted_line_break() {
        assertThatThrownBy(() -> read("\"12\n345\"\n", limits(4, 16)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("field limit");
    }

    @Test
    void resets_field_limit_at_delimiters_but_enforces_whole_record_limit() throws Exception {
        assertThat(read("1234;5678\n", limits(4, 9))).isEqualTo("1234;5678\n");

        assertThatThrownBy(() -> read("1234;5678\n", limits(4, 8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("record limit");
    }

    private DelimitedInputLimits limits(int maximumFieldCharacters, int maximumRecordCharacters) {
        return new DelimitedInputLimits(10, 10, maximumFieldCharacters, maximumRecordCharacters);
    }

    private String read(String input, DelimitedInputLimits limits) throws Exception {
        try (Reader reader = new DelimitedInputLimitingReader(new StringReader(input), DIALECT, limits)) {
            var result = new StringBuilder();
            char[] buffer = new char[3];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }
}
