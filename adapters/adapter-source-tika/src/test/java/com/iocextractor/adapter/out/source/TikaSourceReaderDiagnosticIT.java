package com.iocextractor.adapter.out.source;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import org.apache.tika.exception.UnsupportedFormatException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class TikaSourceReaderDiagnosticIT {

    @TempDir
    Path tempDir;

    @Test
    void preserves_unsupported_format_as_typed_run_failure() throws Exception {
        Path source = Files.writeString(tempDir.resolve("sample.unknown"), "data");
        var failure = new UnsupportedFormatException("unsupported");
        var reader = new TikaSourceReader(new FailingParser(failure), null,
                new DiagnosticFactory(Clock.systemUTC()));

        assertThatThrownBy(() -> reader.readText(source))
                .isInstanceOf(DiagnosticException.class)
                .satisfies(thrown -> {
                    var typed = (DiagnosticException) thrown;
                    assertThat(typed.diagnostic().code()).isEqualTo(SourceDiagnosticCodes.UNSUPPORTED_FORMAT);
                    assertThat(typed.diagnostic().context())
                            .containsEntry("source", source)
                            .containsEntry("format", "unknown");
                    assertThat(typed.getCause()).isSameAs(failure);
                });
    }

    @Test
    void preserves_io_failure_as_typed_read_failure() {
        Path missing = tempDir.resolve("missing.html");
        var reader = new TikaSourceReader(null, new DiagnosticFactory(Clock.systemUTC()));

        assertThatThrownBy(() -> reader.readText(missing))
                .isInstanceOf(DiagnosticException.class)
                .satisfies(thrown -> {
                    var typed = (DiagnosticException) thrown;
                    assertThat(typed.diagnostic().code()).isEqualTo(SourceDiagnosticCodes.READ_FAILED);
                    assertThat(typed.diagnostic().context()).containsEntry("source", missing);
                    assertThat(typed.getCause()).isInstanceOf(IOException.class);
                });
    }

    @Test
    void preserves_filesystem_root_as_typed_read_failure() {
        Path filesystemRoot = tempDir.toAbsolutePath().getRoot();
        var reader = new TikaSourceReader(null, new DiagnosticFactory(Clock.systemUTC()));

        assertThat(filesystemRoot).isNotNull();
        assertThatThrownBy(() -> reader.readText(filesystemRoot))
                .isInstanceOf(DiagnosticException.class)
                .satisfies(thrown -> {
                    var typed = (DiagnosticException) thrown;
                    assertThat(typed.diagnostic().code()).isEqualTo(SourceDiagnosticCodes.READ_FAILED);
                    assertThat(typed.diagnostic().context()).containsEntry("source", filesystemRoot);
                    assertThat(typed.getCause()).isInstanceOf(IllegalArgumentException.class);
                    assertThat(typed.getCause().getMessage()).contains("source path must name a file");
                });
    }

    private record FailingParser(UnsupportedFormatException failure) implements Parser {

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of();
        }

        @Override
        public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                throws IOException, SAXException, UnsupportedFormatException {
            throw failure;
        }
    }
}
