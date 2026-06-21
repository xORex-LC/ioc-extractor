package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurableRowMapperTest {

    private final MatchPolicy stubPolicy = indicator -> new MaskMatch("u:hAS", "h:dAS");

    private ConfigurableRowMapper mapper(List<ColumnSpec> columns) {
        Map<String, ValueProvider> providers = new HashMap<>();
        providers.put("id", new IdValueProvider());
        providers.put("value", new IndicatorValueProvider());
        providers.put("source.label", new SourceLabelValueProvider());
        providers.put("match.url", new MatchUrlValueProvider(stubPolicy));
        providers.put("match.host", new MatchHostValueProvider(stubPolicy));
        Map<String, Transform> transforms = new HashMap<>();
        transforms.put("lower", new LowercaseTransform());
        transforms.put("upper", new UppercaseTransform());
        transforms.put("strip-prefix", new StripPrefixTransform());
        return new ConfigurableRowMapper(columns, providers, transforms);
    }

    private Indicator indicator(String value, IndicatorType type, String label) {
        return new Indicator(value, type, new SourceContext(label, null));
    }

    @Test
    void header_is_column_names() {
        ConfigurableRowMapper m = mapper(List.of(
                new ColumnSpec("id", "id", null, null, null),
                new ColumnSpec("mask", "value", null, null, List.of("lower"))));
        assertThat(m.header()).containsExactly("id", "mask");
    }

    @Test
    void const_is_null_value_lowercased_match_codes_and_source() {
        ConfigurableRowMapper m = mapper(List.of(
                new ColumnSpec("id", "id", null, null, null),
                new ColumnSpec("mask", "value", null, null, List.of("lower")),
                new ColumnSpec("url_match", "match.url", null, null, null),
                new ColumnSpec("host_match", "match.host", null, null, null),
                new ColumnSpec("score", "const", null, null, null),
                new ColumnSpec("source", "source.label", null, null, null)));
        List<String> row = m.toRow(186, indicator("EXAMPLE.com", IndicatorType.DOMAIN, "Письмо X"));
        assertThat(row).containsExactly("186", "example.com", "u:hAS", "h:dAS", null, "Письмо X");
    }

    @Test
    void when_type_gates_hash_columns_and_uppercases() {
        ConfigurableRowMapper m = mapper(List.of(
                new ColumnSpec("hash_md5", "value", null, IndicatorType.MD5, List.of("upper")),
                new ColumnSpec("hash_sha256", "value", null, IndicatorType.SHA256, List.of("upper"))));
        List<String> row = m.toRow(1, indicator("abcdef", IndicatorType.MD5, null));
        assertThat(row).containsExactly("ABCDEF", null);
    }

    @Test
    void strip_prefix_transform() {
        ConfigurableRowMapper m = mapper(List.of(
                new ColumnSpec("source", "source.label", null, null, List.of("strip-prefix:Письмо "))));
        List<String> row = m.toRow(1, indicator("x", IndicatorType.SHA256, "Письмо ФСТЭК"));
        assertThat(row).containsExactly("ФСТЭК");
    }

    @Test
    void unknown_provider_fails_fast() {
        ConfigurableRowMapper m = mapper(List.of(new ColumnSpec("x", "nope", null, null, null)));
        assertThatThrownBy(() -> m.toRow(1, indicator("x", IndicatorType.URL, null)))
                .isInstanceOf(IocExtractorException.class);
    }
}
