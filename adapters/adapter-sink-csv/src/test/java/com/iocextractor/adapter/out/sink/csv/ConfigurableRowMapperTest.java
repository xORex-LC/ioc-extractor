package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.common.IocExtractorException;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.classify.MatchRule;
import com.iocextractor.domain.classify.RuleBasedMatchPolicy;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurableRowMapperTest {

    private ConfigurableRowMapper mapper(List<ColumnSpec> columns) {
        Map<String, ValueProvider> providers = new HashMap<>();
        providers.put("id", new IdValueProvider());
        providers.put("value", new IndicatorValueProvider());
        providers.put("source.label", new SourceLabelValueProvider());
        providers.put("match.url", new MatchUrlValueProvider());
        providers.put("match.host", new MatchHostValueProvider());
        Map<String, Transform> transforms = new HashMap<>();
        transforms.put("lower", new LowercaseTransform());
        transforms.put("upper", new UppercaseTransform());
        transforms.put("strip-prefix", new StripPrefixTransform());
        return new ConfigurableRowMapper(columns, providers, transforms);
    }

    private ClassifiedIndicator indicator(String value, IndicatorType type, String label) {
        var indicator = new Indicator(value, type, new SourceContext(label, null));
        var features = new IndicatorFeatures(value, value, false, false, false, HostKind.REGISTRABLE);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(features, 0, List.of(), new MaskMatch("u:hAS", "h:dAS")));
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

    @Test
    void multiple_columns_reuse_one_materialized_classification() {
        var featureCalls = new AtomicInteger();
        var features = new IndicatorFeatures(
                "example.com", "example.com", false, false, false, HostKind.REGISTRABLE);
        var policy = new RuleBasedMatchPolicy(indicator -> {
            featureCalls.incrementAndGet();
            return features;
        }, List.of(new MatchRule(List.of(), List.of(), new MaskMatch("u:hAS", "h:dAS"))));
        var indicator = new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext(null, null));
        var classified = new ClassifiedIndicator(indicator, policy.classify(indicator));
        var mapper = mapper(List.of(
                new ColumnSpec("url_match", "match.url", null, null, null),
                new ColumnSpec("host_match", "match.host", null, null, null),
                new ColumnSpec("url_match_copy", "match.url", null, null, null)));

        assertThat(mapper.toRow(1, classified)).containsExactly("u:hAS", "h:dAS", "u:hAS");
        assertThat(featureCalls).hasValue(1);
    }
}
