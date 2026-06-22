package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIndicatorFeatureExtractorTest {

    /** Stub: parsing is independent of host kind, so a fixed value isolates the parser. */
    private final HostClassifier stub = host -> HostKind.REGISTRABLE;
    private final IndicatorFeatureExtractor extractor =
            new DefaultIndicatorFeatureExtractor(new DefaultIndicatorNormalizer(), stub);

    private IndicatorFeatures features(String value, IndicatorType type) {
        return extractor.extract(new Indicator(value, type, new SourceContext(null, null)));
    }

    @Test
    void bare_domain() {
        IndicatorFeatures f = features("example.com", IndicatorType.DOMAIN);
        assertThat(f.host()).isEqualTo("example.com");
        assertThat(f.hasPort()).isFalse();
        assertThat(f.hasPath()).isFalse();
        assertThat(f.hasQuery()).isFalse();
    }

    @Test
    void domain_with_path() {
        IndicatorFeatures f = features("example.com/a/b", IndicatorType.DOMAIN);
        assertThat(f.host()).isEqualTo("example.com");
        assertThat(f.hasPath()).isTrue();
        assertThat(f.hasQuery()).isFalse();
    }

    @Test
    void domain_with_query_only() {
        IndicatorFeatures f = features("example.com?x=1", IndicatorType.DOMAIN);
        assertThat(f.host()).isEqualTo("example.com");
        assertThat(f.hasPath()).isFalse();
        assertThat(f.hasQuery()).isTrue();
    }

    @Test
    void path_and_query() {
        IndicatorFeatures f = features("example.com/a?x=1", IndicatorType.URL);
        assertThat(f.host()).isEqualTo("example.com");
        assertThat(f.hasPath()).isTrue();
        assertThat(f.hasQuery()).isTrue();
    }

    @Test
    void ip_with_port() {
        IndicatorFeatures f = features("1.2.3.4:8080", IndicatorType.IPV4);
        assertThat(f.host()).isEqualTo("1.2.3.4");
        assertThat(f.hasPort()).isTrue();
        assertThat(f.hasPath()).isFalse();
    }

    @Test
    void ip_with_port_and_path() {
        IndicatorFeatures f = features("1.2.3.4:8080/w.exe", IndicatorType.IPV4);
        assertThat(f.host()).isEqualTo("1.2.3.4");
        assertThat(f.hasPort()).isTrue();
        assertThat(f.hasPath()).isTrue();
    }

    @Test
    void scheme_stripped_and_token_colon_is_not_a_port() {
        IndicatorFeatures f = features("https://api.telegram.org/bot123:TOKEN", IndicatorType.URL);
        assertThat(f.host()).isEqualTo("api.telegram.org");
        assertThat(f.hasPort()).isFalse(); // ':' lives in the path, not the authority
        assertThat(f.hasPath()).isTrue();
    }
}
