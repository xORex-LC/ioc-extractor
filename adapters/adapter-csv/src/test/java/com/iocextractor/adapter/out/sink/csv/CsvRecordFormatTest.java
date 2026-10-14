package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportQuoting;
import com.iocextractor.application.export.ExportRecordSeparator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvRecordFormatTest {

    @ParameterizedTest
    @CsvSource({"ALL, ALL", "ALL, MINIMAL", "ALL, NONE", "MINIMAL, ALL", "MINIMAL, MINIMAL",
            "MINIMAL, NONE", "NONE, ALL", "NONE, MINIMAL", "NONE, NONE"})
    void applies_header_and_value_quoting_independently(ExportQuoting header, ExportQuoting values) throws Exception {
        var output = new StringBuilder();
        var format = format(ExportRecordSeparator.LF, header, values);

        format.printHeader(output, List.of("id", "value"));
        format.printRecord(output, List.of("1", "example.test"));

        String expectedHeader = header == ExportQuoting.ALL ? "\"id\";\"value\"" : "id;value";
        String expectedValues = values == ExportQuoting.ALL ? "\"1\";\"example.test\"" : "1;example.test";
        assertThat(output.toString()).isEqualTo(expectedHeader + "\n" + expectedValues + "\n");
    }

    @Test
    void preserves_original_crlf_bytes_and_unquoted_nulls() throws Exception {
        var output = new StringBuilder();
        var format = new CsvRecordFormat(new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"));

        format.printHeader(output, List.of("id", "value"));
        format.printRecord(output, Arrays.asList("1", null));

        assertThat(output.toString()).isEqualTo("\"id\";\"value\"\r\n\"1\";NULL\r\n");
    }

    @Test
    void minimal_preserves_special_characters_empty_text_and_literal_null() throws Exception {
        var output = new StringBuilder();
        var values = Arrays.asList("plain", "semi;colon", "a\"b", "one\ntwo", "one\rtwo", "NULL", "", null);

        format(ExportRecordSeparator.LF, ExportQuoting.NONE, ExportQuoting.MINIMAL).printRecord(output, values);

        assertThat(output.toString())
                .isEqualTo("plain;\"semi;colon\";\"a\"\"b\";\"one\ntwo\";\"one\rtwo\";\"NULL\";;NULL\n");
        // A consumer that distinguishes quoted text from an unquoted null marker retains every cell.
        var consumer = CSVFormat.Builder.create().setDelimiter(';').setQuote('"')
                .setNullString("NULL").setQuoteMode(QuoteMode.ALL_NON_NULL).build();
        try (var parser = consumer.parse(new StringReader(output.toString()))) {
            assertThat(parser.getRecords()).singleElement().satisfies(record ->
                    assertThat(record.toList()).containsExactlyElementsOf(values));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"private;value", "private\"value", "private\rvalue", "private\nvalue", "NULL"})
    void none_rejects_ambiguous_values_before_writing_any_part_of_the_record(String value) {
        var output = new StringBuilder("existing\n");

        assertThatThrownBy(() -> format(ExportRecordSeparator.LF, ExportQuoting.ALL, ExportQuoting.NONE)
                .printRecord(output, List.of("1", value)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CSV NONE", "column 2")
                .hasMessageNotContaining("private");
        assertThat(output.toString()).isEqualTo("existing\n");
    }

    @Test
    void none_rejects_an_ambiguous_single_empty_cell_but_preserves_empty_cells_in_a_row() throws Exception {
        var format = format(ExportRecordSeparator.LF, ExportQuoting.NONE, ExportQuoting.NONE);
        var output = new StringBuilder();

        assertThatThrownBy(() -> format.printRecord(output, List.of(""))).isInstanceOf(IOException.class);
        format.printRecord(output, Arrays.asList("", "", null));

        assertThat(output.toString()).isEqualTo(";;NULL\n");
    }

    @Test
    void validates_unquoted_headers_independently_of_quoted_values() throws Exception {
        var output = new StringBuilder();
        var format = format(ExportRecordSeparator.CRLF, ExportQuoting.NONE, ExportQuoting.ALL);

        assertThatThrownBy(() -> format.printHeader(output, List.of("unsafe;header")))
                .isInstanceOf(IOException.class);
        format.printHeader(output, List.of("NULL"));
        format.printRecord(output, List.of("unsafe;value"));

        assertThat(output.toString()).isEqualTo("NULL\r\n\"unsafe;value\"\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"N;A", "\"NULL\"", "N\rA", "N\nA"})
    void rejects_a_null_marker_that_would_break_the_csv_grammar(String nullLiteral) {
        assertThatThrownBy(() -> new CsvRecordFormat(new ExportFormat("csv", "UTF-8", ";", "\"", nullLiteral)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null marker");
    }

    private CsvRecordFormat format(ExportRecordSeparator separator, ExportQuoting header, ExportQuoting values) {
        return new CsvRecordFormat(new ExportFormat("csv", "UTF-8", ";", "\"", "NULL", separator, header, values));
    }
}
