package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.regex.JdkRegexPatternEngine;
import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ContractTest
class PatternEngineConfigurationTest {

    @ParameterizedTest(name = "{2}")
    @MethodSource("engineSelections")
    void compositionRootSelectsConfiguredEngine(Map<String, Object> overrides,
                                                Class<? extends PatternEngine> expectedType,
                                                String expectedId) throws Exception {
        IocProperties properties = bind(overrides);

        PatternEngine engine = new AppConfig().patternEngine(properties);

        assertThat(engine).isExactlyInstanceOf(expectedType);
        assertThat(engine.id()).isEqualTo(expectedId);
    }

    @Test
    void configuredDetectionPatternsHaveEquivalentContractsOnBothEngines() throws Exception {
        IocProperties properties = bind(Map.of());
        Map<IndicatorType, PatternCase> cases = detectionPatternCases();
        assertThat(properties.patterns().keySet()).containsExactlyInAnyOrderElementsOf(cases.keySet());

        cases.forEach((type, patternCase) -> assertPatternContract(
                "ioc.patterns." + type,
                properties.patterns().get(type),
                patternCase));
    }

    @Test
    void configuredSectionMarkersHaveEquivalentContractsOnBothEngines() throws Exception {
        IocProperties properties = bind(Map.of());
        List<PatternCase> cases = sectionMarkerCases();
        assertThat(properties.source().sectionMarkers()).hasSameSizeAs(cases);

        for (int index = 0; index < cases.size(); index++) {
            assertPatternContract(
                    "ioc.source.section-markers[" + index + "]",
                    properties.source().sectionMarkers().get(index),
                    cases.get(index));
        }
    }

    private static Stream<Arguments> engineSelections() {
        return Stream.of(
                arguments(Map.<String, Object>of(), Re2jPatternEngine.class, "re2j"),
                arguments(Map.<String, Object>of("ioc.engine", "jdk"), JdkRegexPatternEngine.class, "jdk"));
    }

    private static Map<IndicatorType, PatternCase> detectionPatternCases() {
        Map<IndicatorType, PatternCase> cases = new EnumMap<>(IndicatorType.class);
        cases.put(IndicatorType.SHA256, repeatedHexCase(64));
        cases.put(IndicatorType.SHA1, repeatedHexCase(40));
        cases.put(IndicatorType.MD5, repeatedHexCase(32));
        cases.put(IndicatorType.URL, new PatternCase(
                "URL https://evil.example/a?x=1, end",
                "https://evil.example/a?x=1",
                "URL ftp://evil.example/a?x=1"));
        cases.put(IndicatorType.IPV4, new PatternCase(
                "Address 192.0.2.10:8443/path end",
                "192.0.2.10:8443/path",
                "Address 192.0.2 end"));
        cases.put(IndicatorType.DOMAIN, new PatternCase(
                "Host sub.example.org/path end",
                "sub.example.org/path",
                "Host localhost end"));
        return cases;
    }

    private static PatternCase repeatedHexCase(int length) {
        String value = "a".repeat(length);
        return new PatternCase("Hash " + value + ".", value, "Hash " + "a".repeat(length - 1) + ".");
    }

    private static List<PatternCase> sectionMarkerCases() {
        return List.of(
                new PatternCase("До БИБ-123 после", "БИБ-123", "До БИБ-X после"),
                new PatternCase(
                        "До Письмо ФСТЭК России от 01.02.2026 № 12/34 после",
                        "Письмо ФСТЭК России от 01.02.2026 № 12/34",
                        "До Письмо ФСТЭК России без номера после"));
    }

    private static void assertPatternContract(String name, String regex, PatternCase patternCase) {
        Span expected = patternCase.expectedSpan();
        for (PatternEngine engine : engines()) {
            PatternEngine.Compiled compiled = engine.compile(regex);
            assertThat(compiled.findAll(patternCase.matchingText()))
                    .as("%s positive contract on %s", name, engine.id())
                    .containsExactly(expected);
            assertThat(compiled.findAll(patternCase.nonMatchingText()))
                    .as("%s negative contract on %s", name, engine.id())
                    .isEmpty();
        }
    }

    private static List<PatternEngine> engines() {
        return List.of(new Re2jPatternEngine(), new JdkRegexPatternEngine());
    }

    private static IocProperties bind(Map<String, Object> overrides) throws Exception {
        var defaults = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("overrides", overrides));
        sources.addLast(defaults);
        ApplicationConversionService conversionService = new ApplicationConversionService();
        conversionService.addConverter(String.class, IdStart.class, IdStart::parse);
        conversionService.addConverter(Number.class, IdStart.class, IdStart::from);
        return new Binder(ConfigurationPropertySources.from(sources), null, conversionService)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }

    private record PatternCase(String matchingText, String expectedValue, String nonMatchingText) {

        private Span expectedSpan() {
            int start = matchingText.indexOf(expectedValue);
            if (start < 0) {
                throw new IllegalStateException("expected value is absent from matching fixture: " + expectedValue);
            }
            return new Span(start, start + expectedValue.length(), expectedValue);
        }
    }
}
