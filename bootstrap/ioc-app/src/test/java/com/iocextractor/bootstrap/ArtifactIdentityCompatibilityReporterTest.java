package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactIdentityCompatibilityReporterTest {

    private final Logger logger =
            (Logger) LoggerFactory.getLogger(ArtifactIdentityCompatibilityReporter.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void warnsForEachLegacyArtifactWithoutConfigurationValues() throws Exception {
        IocProperties properties = defaults();
        IocProperties.ArtifactIdentity legacyIdentity = new IocProperties.ArtifactIdentity(List.of(
                legacy("masks", null, "mask"),
                legacy("ip_list", null, "ip"),
                legacy("address_blacklist", ArtifactKeyMode.FIRST_NON_EMPTY,
                        "forbidden_url", "forbidden_ip"),
                legacy("hashes", ArtifactKeyMode.FIRST_NON_EMPTY,
                        "hash_md5", "hash_sha1", "hash_sha256")));
        ArtifactIdentityCompatibilityReporter reporter = new ArtifactIdentityCompatibilityReporter(
                withArtifactIdentity(properties, legacyIdentity));
        ListAppender<ILoggingEvent> appender = appender();

        reporter.reportCompatibility();

        assertThat(appender.list).hasSize(4).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains(V020ArtifactIdentityCompatibility.DIAGNOSTIC_CODE)
                    .contains("v0.2.0 identity shape")
                    .doesNotContain("forbidden_url", "hash_sha256");
        });
    }

    private IocProperties.ArtifactIdentity.Artifact legacy(
            String name,
            ArtifactKeyMode keyMode,
            String... columns) {
        return new IocProperties.ArtifactIdentity.Artifact(
                name, List.of(columns), keyMode, null, null, null);
    }

    private IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        ApplicationConversionService conversionService = new ApplicationConversionService();
        conversionService.addConverter(String.class, IdStart.class, IdStart::parse);
        conversionService.addConverter(Number.class, IdStart.class, IdStart::from);
        return new Binder(ConfigurationPropertySources.from(source), null, conversionService)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }

    private IocProperties withArtifactIdentity(
            IocProperties source,
            IocProperties.ArtifactIdentity artifactIdentity) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), source.sink(), source.pipeline(), source.ingestion(),
                artifactIdentity, source.dataframeImport(), source.export(), source.sync(), source.maintenance(),
                source.lifecycle(), source.observability());
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
