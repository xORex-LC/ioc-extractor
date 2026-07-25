package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IocPropertiesTest {

    @Test
    void defaultConfigurationBindsPipelineDedup() throws Exception {
        IocProperties properties = bind(Map.of());

        assertThat(properties.pipeline().deduplicate()).isTrue();
        assertThat(properties.pipeline().failurePolicy()).isEqualTo(PipelineFailurePolicy.FAIL_FAST);
        assertThat(properties.pipeline().maxDiagnosticsPerRun()).isEqualTo(10_000);
        assertThat(properties.ingestion().detect().useWatchService()).isFalse();
    }

    private IocProperties bind(Map<String, Object> overrides) throws Exception {
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
}
