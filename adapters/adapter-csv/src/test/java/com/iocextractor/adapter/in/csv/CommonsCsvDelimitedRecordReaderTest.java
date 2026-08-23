package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.port.out.dataframeimport.DelimitedReadCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonsCsvDelimitedRecordReaderTest {

    private static final DelimitedDialect DIALECT = new DelimitedDialect(
            ';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL"));

    @TempDir
    Path tempDir;

    @Test
    void streams_rows_in_source_order_after_alias_resolution() throws Exception {
        Path source = write("comment;IP Address;score\r\nignored;192.0.2.1;10\r\nnone;198.51.100.2;20\r\n");
        var records = new ArrayList<ImportDelimitedRecord>();

        reader(source).read(command(new DataframeImportCatalogDraft.Recognition(
                List.of("ip", "score"), List.of(), List.of("comment"), Map.of("IP Address", "ip"))),
                records::add);

        assertThat(records).containsExactly(
                new ImportDelimitedRecord(2, Map.of("ip", "192.0.2.1", "score", "10")),
                new ImportDelimitedRecord(3, Map.of("ip", "198.51.100.2", "score", "20")));
    }

    @Test
    void rejects_malformed_bytes_instead_of_replacing_them() throws Exception {
        Path source = tempDir.resolve("malformed.csv");
        Files.write(source, new byte[]{'i', 'p', '\n', (byte) 0xC3, 0x28, '\n'});

        assertThatThrownBy(() -> reader(source).read(command(recognition()), ignored -> { }))
                .isInstanceOf(DelimitedRecordReadException.class)
                .hasMessageContaining("malformed or unmappable bytes");
    }

    @Test
    void rejects_separator_outside_the_declared_policy() throws Exception {
        Path source = write("ip\n192.0.2.1\n");
        DelimitedDialect crlf = new DelimitedDialect(
                ';', '"', ImportRecordSeparator.CRLF, true, List.of());
        DelimitedReadCommand command = new DelimitedReadCommand(
                new ImportSnapshotReference("snapshot"), "UTF-8", crlf, recognition());

        assertThatThrownBy(() -> reader(source).read(command, ignored -> { }))
                .isInstanceOf(DelimitedRecordReadException.class)
                .hasMessageContaining("declared contract");
    }

    @Test
    void rejects_ambiguous_or_unexpected_headers_without_echoing_them() throws Exception {
        Path source = write("ip;IP Address;operator-secret\nip-a;ip-b;secret\n");
        var signature = new DataframeImportCatalogDraft.Recognition(
                List.of("ip"), List.of(), List.of(), Map.of("IP Address", "ip"));

        assertThatThrownBy(() -> reader(source).read(command(signature), ignored -> { }))
                .isInstanceOf(DelimitedRecordReadException.class)
                .hasMessageContaining("missing=0, unexpected=1, duplicate=1")
                .hasMessageNotContaining("operator-secret");
    }

    @Test
    void consumer_failure_stops_the_lazy_parser_immediately() throws Exception {
        Path source = write("ip\n192.0.2.1\n192.0.2.2\n192.0.2.3\n");
        var callbacks = new AtomicInteger();
        var stop = new IllegalStateException("stop staging");

        assertThatThrownBy(() -> reader(source).read(command(recognition()), record -> {
            if (callbacks.incrementAndGet() == 2) {
                throw stop;
            }
        })).isSameAs(stop);
        assertThat(callbacks).hasValue(2);
    }

    private CommonsCsvDelimitedRecordReader reader(Path source) {
        return new CommonsCsvDelimitedRecordReader(ignored -> source);
    }

    private DelimitedReadCommand command(DataframeImportCatalogDraft.Recognition recognition) {
        return new DelimitedReadCommand(
                new ImportSnapshotReference("snapshot"), "UTF-8", DIALECT, recognition);
    }

    private DataframeImportCatalogDraft.Recognition recognition() {
        return new DataframeImportCatalogDraft.Recognition(
                List.of("ip"), List.of(), List.of(), Map.of());
    }

    private Path write(String content) throws Exception {
        Path source = tempDir.resolve("delivery-" + System.nanoTime() + ".csv");
        Files.writeString(source, content, StandardCharsets.UTF_8);
        return source;
    }
}
