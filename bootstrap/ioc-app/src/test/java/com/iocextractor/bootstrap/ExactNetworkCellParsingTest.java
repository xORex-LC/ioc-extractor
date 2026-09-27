package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.regex.JdkRegexPatternEngine;
import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.application.processing.ExactIndicatorParser;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.model.IndicatorType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class ExactNetworkCellParsingTest {
    @Test
    void configuredPatternsAgreeAcrossEnginesForDocumentsAndWholeCells() throws Exception {
        var defaults = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        var sources = new MutablePropertySources();
        sources.addLast(defaults);
        ApplicationConversionService conversion = new ApplicationConversionService();
        conversion.addConverter(String.class, IdStart.class, IdStart::parse);
        conversion.addConverter(Number.class, IdStart.class, IdStart::from);
        IocProperties properties = new Binder(ConfigurationPropertySources.from(sources), null, conversion)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("Default IOC configuration did not bind"));
        for (PatternEngine engine : List.of(new Re2jPatternEngine(), new JdkRegexPatternEngine())) {
            var extractor = new AppConfig().indicatorExtractor(engine, properties);
            var exact = new ExactIndicatorParser(extractor, new NetworkAddressParser());

            assertThat(extractor.extract("See https://best-malware.com/troyan.exe, now")
                    .indicators()).extracting(raw -> raw.value())
                    .contains("https://best-malware.com/troyan.exe");
            assertThat(extractor.extract("IOC 10.93.12.187:9090/clean-prometheus/no-virus/true;")
                    .indicators()).extracting(raw -> raw.value())
                    .contains("10.93.12.187:9090/clean-prometheus/no-virus/true");
            assertThat(exact.parse(" domain.test:443/path ").indicator().type())
                    .as(engine.id()).isEqualTo(IndicatorType.DOMAIN);
            assertThat(exact.parse("10.93.12.187:9090/path").indicator().type())
                    .as(engine.id()).isEqualTo(IndicatorType.IPV4);
            assertThat(exact.parse("domain.test:abc/path").isAvailable()).as(engine.id()).isFalse();
            assertThat(exact.parse("10.93.12.187:9090/path trailing").isAvailable())
                    .as(engine.id()).isFalse();
            assertThat(exact.parse("https://user@domain.test/path").isAvailable())
                    .as(engine.id()).isFalse();
        }
    }
}
