package com.iocextractor.domain.extract;

import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegexIndicatorExtractorTest {

    private IndicatorExtractor extractor() {
        Map<IndicatorType, String> patterns = new LinkedHashMap<>();
        patterns.put(IndicatorType.SHA256, "\\b[0-9a-fA-F]{64}\\b");
        patterns.put(IndicatorType.URL, "https?://[^\\s;,<>\"']+");
        patterns.put(IndicatorType.IPV4, "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?:[:/][^\\s;,<>\"']*)?");
        patterns.put(IndicatorType.DOMAIN, "\\b(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[^\\s;,<>\"']*)?");
        return new RegexIndicatorExtractor(new Re2jPatternEngine(), patterns);
    }

    @Test
    void extracts_each_type() {
        String hash = "c9e92d24fefd87f1817428ceb56e175ddcf19ecd96d80418824a553588aa6067";
        String text = "url hxxp refanged: https://evil.com/a ip 1.2.3.4:8080 host bare.example.org hash " + hash;

        List<RawIndicator> result = extractor().extract(text);

        assertThat(result).extracting(RawIndicator::type)
                .contains(IndicatorType.URL, IndicatorType.IPV4, IndicatorType.DOMAIN, IndicatorType.SHA256);
    }

    @Test
    void url_host_is_not_re_emitted_as_bare_domain() {
        List<RawIndicator> result = extractor().extract("go to https://evil.com/path now");

        assertThat(result).extracting(RawIndicator::value)
                .containsExactly("https://evil.com/path");
        assertThat(result).extracting(RawIndicator::type)
                .doesNotContain(IndicatorType.DOMAIN);
    }
}
