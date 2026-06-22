package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactFilterTest {

    @TempDir
    Path tempDir;

    @Test
    void csv_sink_applies_feature_filter_after_type_filter() throws Exception {
        RowMapper mapper = new RowMapper() {
            @Override
            public List<String> header() {
                return List.of("id", "value");
            }

            @Override
            public List<String> toRow(long id, Indicator indicator) {
                return List.of(Long.toString(id), indicator.value());
            }
        };
        Path output = tempDir.resolve("filtered.csv");
        var sink = new CsvIocSink(
                "filtered",
                output,
                Set.of(IndicatorType.IPV4),
                new ArtifactFilter(List.of(indicator -> indicator.value().equals("1.2.3.4")), List.of()),
                mapper,
                new IdGenerator(IdGenerator.Strategy.ASCENDING, 10),
                CSVFormat.Builder.create()
                        .setDelimiter(';')
                        .setQuote('"')
                        .setRecordSeparator("\n")
                        .build());

        int written = sink.write(List.of(
                indicator("1.2.3.4", IndicatorType.IPV4),
                indicator("5.6.7.8", IndicatorType.IPV4),
                indicator("example.com", IndicatorType.DOMAIN)));

        assertThat(written).isEqualTo(1);
        assertThat(Files.readString(output)).contains("1.2.3.4").doesNotContain("5.6.7.8");
    }

    private Indicator indicator(String value, IndicatorType type) {
        return new Indicator(value, type, SourceContext.UNKNOWN);
    }
}
