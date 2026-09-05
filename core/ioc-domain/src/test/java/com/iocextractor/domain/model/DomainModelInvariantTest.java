package com.iocextractor.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelInvariantTest {

    @ParameterizedTest(name = "{0} is {1} / {2}")
    @MethodSource("indicatorTypeMetadata")
    void indicator_type_metadata_is_stable(
            IndicatorType type,
            IndicatorCategory category,
            String stixType) {
        assertThat(type.category()).isEqualTo(category);
        assertThat(type.stixType()).isEqualTo(stixType);
    }

    @Test
    void dedup_identity_uses_type_and_value_but_not_provenance() {
        Indicator original = indicator("same.test", IndicatorType.DOMAIN, "first");
        Indicator sameIdentity = indicator("same.test", IndicatorType.DOMAIN, "second");
        Indicator differentType = indicator("same.test", IndicatorType.URL, "first");
        Indicator differentValue = indicator("other.test", IndicatorType.DOMAIN, "first");

        assertThat(sameIdentity.dedupKey()).isEqualTo(original.dedupKey());
        assertThat(differentType.dedupKey()).isNotEqualTo(original.dedupKey());
        assertThat(differentValue.dedupKey()).isNotEqualTo(original.dedupKey());
    }

    @Test
    void replacing_a_source_label_preserves_the_section() {
        SourceContext source = new SourceContext("old", "section-a");

        assertThat(source.withLabel("new"))
                .isEqualTo(new SourceContext("new", "section-a"));
    }

    private static Stream<Arguments> indicatorTypeMetadata() {
        return Stream.of(
                Arguments.of(IndicatorType.IPV4, IndicatorCategory.NETWORK, "ipv4-addr"),
                Arguments.of(IndicatorType.DOMAIN, IndicatorCategory.NETWORK, "domain-name"),
                Arguments.of(IndicatorType.URL, IndicatorCategory.NETWORK, "url"),
                Arguments.of(IndicatorType.MD5, IndicatorCategory.FILE, "file:hashes.MD5"),
                Arguments.of(IndicatorType.SHA1, IndicatorCategory.FILE, "file:hashes.'SHA-1'"),
                Arguments.of(IndicatorType.SHA256, IndicatorCategory.FILE, "file:hashes.'SHA-256'"));
    }

    private static Indicator indicator(String value, IndicatorType type, String label) {
        return new Indicator(value, type, new SourceContext(label, "section"));
    }
}
