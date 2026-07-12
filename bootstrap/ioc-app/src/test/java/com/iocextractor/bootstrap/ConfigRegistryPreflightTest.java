package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigRegistryPreflightTest {

    @Test
    void acceptsDefaultRegistryBackedConfiguration() throws Exception {
        contextRunner(defaults()).run(context -> {
            assertThat(context).hasSingleBean(IocProperties.class);
            assertThat(context).hasSingleBean(ConfigRegistryPreflight.class);
        });
    }

    @Test
    void rejectsUnknownClassifyPredicateAtStartup() throws Exception {
        contextRunner(withClassifyPredicate(defaults(), "has-secret-sauce"))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "ioc.classify.rules[0].when[0]",
                        "has-secret-sauce",
                        "allowed values",
                        "has-query",
                        "use a registered classify predicate"));
    }

    @Test
    void rejectsUnknownArtifactIncludeAndExcludePredicatesAtStartup() throws Exception {
        contextRunner(withArtifactFilters(defaults(), "unknown-exclude", "unknown-include"))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "ioc.sink.artifacts[0].exclude[0]",
                        "unknown-exclude",
                        "ioc.sink.artifacts[1].include[0]",
                        "unknown-include",
                        "is-bare-ip"));
    }

    @Test
    void rejectsUnknownColumnProviderAtStartup() throws Exception {
        contextRunner(withColumnProvider(defaults(), "network.mask"))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "ioc.sink.artifacts[0].columns[1].from",
                        "network.mask",
                        "value",
                        "const"));
    }

    @Test
    void rejectsUnknownTransformAtStartupAndParsesTransformArgsByName() throws Exception {
        contextRunner(withColumnTransform(defaults(), "normalize-host:strict"))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "ioc.sink.artifacts[0].columns[1].transform[0]",
                        "normalize-host",
                        "lower-host",
                        "name:arg"));
    }

    @Test
    void rejectsConditionalOrTransformedDeferredIdColumn() throws Exception {
        IocProperties source = defaults();
        IocProperties.Sink.Artifact.Column id = source.sink().artifacts().getFirst().columns().getFirst();
        var invalid = new IocProperties.Sink.Artifact.Column(
                id.name(), id.from(), id.value(), id.type(), IndicatorType.MD5, List.of("upper"));

        contextRunner(withMasksColumnAt(source, 0, invalid))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "ioc.sink.artifacts[0].columns[0].when-type",
                        "ioc.sink.artifacts[0].columns[0].transform",
                        "deferred 'id' provider"));
    }

    @Test
    void reportsRegistryMistakesWithoutRuntimeImplementationJargon() throws Exception {
        contextRunner(withRegistryMistakes(defaults()))
                .run(context -> {
                    String messages = causeMessages(context.getStartupFailure());
                    assertThat(messages)
                            .contains(
                                    "CONFIG.REGISTRY",
                                    "ioc.classify.rules[0].when[0]",
                                    "ioc.sink.artifacts[0].exclude[0]",
                                    "ioc.sink.artifacts[0].columns[1].from",
                                    "ioc.sink.artifacts[0].columns[1].transform[0]")
                            .doesNotContain(
                                    "stage 11",
                                    "WriteArtifactsStage",
                                    "ConfigurableRowMapper",
                                    "resolvePredicates",
                                    "AppConfig");
                });
    }

    private ApplicationContextRunner contextRunner(IocProperties props) {
        return new ApplicationContextRunner()
                .withBean(IocProperties.class, () -> props)
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues("logging.level.root=OFF");
    }

    private static void assertRegistryFailure(Throwable failure, String... snippets) {
        assertThat(failure).isNotNull();
        assertThat(causeMessages(failure))
                .contains("CONFIG.REGISTRY")
                .contains(snippets);
    }

    private static String causeMessages(Throwable throwable) {
        List<String> messages = new ArrayList<>();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
            current = current.getCause();
        }
        return String.join("\n", messages);
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

    private IocProperties withClassifyPredicate(IocProperties source, String predicate) {
        List<IocProperties.Classify.Rule> rules = new ArrayList<>(source.classify().rules());
        IocProperties.Classify.Rule first = rules.getFirst();
        rules.set(0, new IocProperties.Classify.Rule(List.of(predicate), first.urlMatch(), first.hostMatch()));
        return withClassify(source, new IocProperties.Classify(rules));
    }

    private IocProperties withArtifactFilters(IocProperties source, String exclude, String include) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        IocProperties.Sink.Artifact masks = artifacts.get(0);
        artifacts.set(0, copyArtifact(masks, masks.include(), List.of(exclude), masks.columns()));
        IocProperties.Sink.Artifact ipList = artifacts.get(1);
        artifacts.set(1, copyArtifact(ipList, List.of(include), ipList.exclude(), ipList.columns()));
        return withSink(source, new IocProperties.Sink(source.sink().csv(), artifacts));
    }

    private IocProperties withColumnProvider(IocProperties source, String provider) {
        return withMasksColumn(source, copyColumn(source.sink().artifacts().getFirst().columns().get(1),
                provider, List.of("lower-host")));
    }

    private IocProperties withColumnTransform(IocProperties source, String transform) {
        return withMasksColumn(source, copyColumn(source.sink().artifacts().getFirst().columns().get(1),
                "value", List.of(transform)));
    }

    private IocProperties withRegistryMistakes(IocProperties source) {
        IocProperties result = withClassifyPredicate(source, "unknown-classify");
        result = withArtifactFilters(result, "unknown-filter", "is-bare-ip");
        return withMasksColumn(result, copyColumn(result.sink().artifacts().getFirst().columns().get(1),
                "unknown-provider", List.of("unknown-transform:arg")));
    }

    private IocProperties withMasksColumn(IocProperties source, IocProperties.Sink.Artifact.Column replacement) {
        return withMasksColumnAt(source, 1, replacement);
    }

    private IocProperties withMasksColumnAt(IocProperties source,
                                             int columnIndex,
                                             IocProperties.Sink.Artifact.Column replacement) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        IocProperties.Sink.Artifact masks = artifacts.getFirst();
        List<IocProperties.Sink.Artifact.Column> columns = new ArrayList<>(masks.columns());
        columns.set(columnIndex, replacement);
        artifacts.set(0, copyArtifact(masks, masks.include(), masks.exclude(), columns));
        return withSink(source, new IocProperties.Sink(source.sink().csv(), artifacts));
    }

    private IocProperties.Sink.Artifact copyArtifact(IocProperties.Sink.Artifact source,
                                                     List<String> include,
                                                     List<String> exclude,
                                                     List<IocProperties.Sink.Artifact.Column> columns) {
        return new IocProperties.Sink.Artifact(
                source.name(), source.enabled(), source.path(), source.accepts(), include, exclude,
                source.id(), columns);
    }

    private IocProperties.Sink.Artifact.Column copyColumn(IocProperties.Sink.Artifact.Column source,
                                                          String provider,
                                                          List<String> transforms) {
        return new IocProperties.Sink.Artifact.Column(
                source.name(), provider, source.value(), source.type(), source.whenType(), transforms);
    }

    private IocProperties withClassify(IocProperties source, IocProperties.Classify classify) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), classify, source.sink(), source.pipeline(), source.ingestion(),
                source.artifactIdentity(), source.export(), source.sync(), source.maintenance(), source.observability());
    }

    private IocProperties withSink(IocProperties source, IocProperties.Sink sink) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), sink, source.pipeline(), source.ingestion(),
                source.artifactIdentity(), source.export(), source.sync(), source.maintenance(), source.observability());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ConfigPreflightConfiguration.class)
    static class TestConfig {
    }
}
