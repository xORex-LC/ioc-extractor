package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CsvIocSinkCharsetTest {

    @TempDir
    Path tempDir;

    private static final Charset CP1251 = Charset.forName("windows-1251");

    @Test
    void writes_output_in_the_configured_charset() throws Exception {
        Path output = tempDir.resolve("cyrillic.csv");
        // A Cyrillic source label, as attributed from a Russian document.
        String cyrillic = "ФСТЭК";
        var sink = sinkFor(output, CP1251, cyrillic);

        sink.write(List.of(indicator("1.2.3.4")));

        byte[] bytes = Files.readAllBytes(output);
        // Round-trips when decoded with the same charset...
        assertThat(new String(bytes, CP1251)).contains(cyrillic);
        // ...and is genuinely cp1251 (single-byte), not UTF-8.
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain(cyrillic);
    }

    @Test
    void replaces_unmappable_characters_instead_of_failing() throws Exception {
        Path output = tempDir.resolve("unmappable.csv");
        // An emoji cannot be encoded in cp1251; the write must not throw.
        var sink = sinkFor(output, CP1251, "bad😀label");

        int written = sink.write(List.of(indicator("1.2.3.4")));

        assertThat(written).isEqualTo(1);
        assertThat(Files.exists(output)).isTrue();
    }

    private CsvIocSink sinkFor(Path output, Charset charset, String label) {
        RowMapper mapper = new RowMapper() {
            @Override
            public List<String> header() {
                return List.of("ip", "source");
            }

            @Override
            public List<String> toRow(long id, Indicator indicator) {
                return List.of(indicator.value(), label);
            }
        };
        return new CsvIocSink(
                "ips",
                output,
                Set.of(IndicatorType.IPV4),
                ArtifactFilter.none(),
                mapper,
                new IdGenerator(IdGenerator.Strategy.ASCENDING, 1),
                CSVFormat.Builder.create().setDelimiter(';').setQuote('"').setRecordSeparator("\n").build(),
                charset);
    }

    private Indicator indicator(String value) {
        return new Indicator(value, IndicatorType.IPV4, new SourceContext(null, null));
    }
}
