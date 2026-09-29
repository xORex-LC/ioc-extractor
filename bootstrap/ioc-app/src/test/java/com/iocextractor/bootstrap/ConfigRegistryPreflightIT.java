package com.iocextractor.bootstrap;

import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;

import com.iocextractor.application.tck.junit.IntegrationTest;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ConfigRegistryPreflightIT {

    @Test
    void acceptsDefaultRegistryBackedConfiguration() throws Exception {
        contextRunner(defaults()).run(context -> {
            assertThat(context).hasSingleBean(IocProperties.class);
            assertThat(context).hasSingleBean(ConfigRegistryPreflight.class);
            assertThat(context).hasSingleBean(ProcessingPlanBindings.class);
            assertThat(context.getBean(ProcessingPlanBindings.class).selectedDocumentPlan()).isEmpty();
        });
    }

    @Test
    void rejectsImportRouteWhoseBranchExceedsContractOutputs() throws Exception {
        contextRunner(withImportRoute(defaults(), "masks", "selected"))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "processed-route must bind exactly one branch per authorized output artifact"));
    }

    @Test
    void admitsMatchingImportRouteAndRejectsMissingPlan() throws Exception {
        IocProperties source = defaults();
        contextRunner(withImportRoute(source, "ip_list", "selected"))
                .run(context -> assertThat(context).hasNotFailed());
        contextRunner(withImportRoute(source, "ip_list", "missing"))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "processed-route.plan must reference a valid named IOC plan"));
    }

    @Test
    void importRoutePreflightHandlesIncompleteBindingsAndDuplicateDestinations() throws Exception {
        IocProperties source = defaults();
        var output = new DataframeImportCatalogDraft.RouteOutput("ip_list", List.of("ip"));
        contextRunner(withImportRoute(source, "ip_list", null, List.of(output), null))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "processed-route.plan must reference a valid named IOC plan"));
        contextRunner(withImportRoute(source, "ip_list", "selected", null, null))
                .run(context -> assertThat(context).hasNotFailed());
        var duplicate = new IocProcessingProperties.Branch("other", "ip_list", "original", null, Map.of());
        contextRunner(withImportRoute(source, "ip_list", "selected", List.of(output), duplicate))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "processed-route must bind exactly one branch per authorized output artifact"));
    }

    @Test
    void importRoutePreflightSkipsMissingContractsAndIncludesDefaultDestination() throws Exception {
        IocProperties source = defaults();
        var original = source.dataframeImport();
        var absentCatalog = new IocProperties.DataframeImport(false, original.sources(),
                original.authorityProfiles(), null, original.runtime());
        contextRunner(withDataframeImport(source, absentCatalog))
                .run(context -> assertThat(context).hasNotFailed());
        var nullContract = new IocProperties.DataframeImport(false, original.sources(),
                original.authorityProfiles(), java.util.Arrays.asList((IocProperties.DataframeImport.Contract) null),
                original.runtime());
        contextRunner(withDataframeImport(source, nullContract))
                .run(context -> assertThat(context).hasNotFailed());

        var fallback = new IocProcessingProperties.Branch("fallback", "masks", "original", null, Map.of());
        var outputs = List.of(new DataframeImportCatalogDraft.RouteOutput("ip_list", List.of("ip")),
                new DataframeImportCatalogDraft.RouteOutput("masks", List.of("mask")));
        contextRunner(withImportRoute(source, "ip_list", "selected", outputs, fallback, true))
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static IocProperties withImportRoute(IocProperties source, String destination, String routePlan) {
        return withImportRoute(source, destination, routePlan,
                List.of(new DataframeImportCatalogDraft.RouteOutput("ip_list", List.of("ip"))), null);
    }

    private static IocProperties withImportRoute(IocProperties source, String destination, String routePlan,
                                                 List<DataframeImportCatalogDraft.RouteOutput> outputs,
                                                 IocProcessingProperties.Branch additionalBranch) {
        return withImportRoute(source, destination, routePlan, outputs, additionalBranch, false);
    }

    private static IocProperties withImportRoute(IocProperties source, String destination, String routePlan,
                                                 List<DataframeImportCatalogDraft.RouteOutput> outputs,
                                                 IocProcessingProperties.Branch additionalBranch,
                                                 boolean asDefault) {
        var route = new DataframeImportCatalogDraft.ProcessedRoute(
                routePlan,
                List.of(new DataframeImportCatalogDraft.RouteInput(
                        "ip_list", "ip")),
                outputs);
        var contract = new IocProperties.DataframeImport.Contract("example", 1, "UTF-8",
                null, null, com.iocextractor.application.dataframeimport.model.ImportProcessingMode.PROCESSED,
                null, null, null, null, false, null, null, List.of(), null, route);
        var original = source.dataframeImport();
        var imports = new IocProperties.DataframeImport(false, original.sources(),
                original.authorityProfiles(), List.of(contract), original.runtime());
        var omissions = source.sink().artifacts().stream()
                .filter(IocProperties.Sink.Artifact::enabled)
                .map(IocProperties.Sink.Artifact::name)
                .filter(name -> !name.equals(destination)
                        && (additionalBranch == null || !name.equals(additionalBranch.artifact()))).toList();
        var branch = new IocProcessingProperties.Branch("bound", destination, "original", null, Map.of());
        var branches = additionalBranch == null || asDefault ? List.of(branch) : List.of(branch, additionalBranch);
        var plan = new IocProcessingProperties.Plan("selected", List.of(),
                List.of(new IocProcessingProperties.Classification("original", "configured")),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.FIRST,
                        new IocProcessingProperties.OnUnmatched(
                                asDefault ? IocProcessingProperties.Action.ROUTE : IocProcessingProperties.Action.SKIP,
                                asDefault ? additionalBranch.id() : null),
                        branches, asDefault ? additionalBranch : null), omissions);
        return new IocProperties(source.engine(), source.runtime(), source.storage(),
                source.source(), source.refang(), source.patterns(), source.classify(), source.sink(),
                source.pipeline(), source.ingestion(), source.artifactIdentity(), imports,
                source.export(), source.sync(), source.maintenance(), source.lifecycle(),
                source.observability(), new IocProcessingProperties(null, List.of(plan)));
    }

    private static IocProperties withDataframeImport(IocProperties source,
                                                     IocProperties.DataframeImport replacement) {
        return new IocProperties(source.engine(), source.runtime(), source.storage(),
                source.source(), source.refang(), source.patterns(), source.classify(), source.sink(),
                source.pipeline(), source.ingestion(), source.artifactIdentity(), replacement,
                source.export(), source.sync(), source.maintenance(), source.lifecycle(),
                source.observability(), source.processing());
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
    void rejectsAmbiguousAndUnknownColumnGatesTogether() throws Exception {
        IocProperties source = defaults();
        IocProperties.Sink.Artifact.Column mask = source.sink().artifacts().getFirst().columns().get(1);
        var invalid = new IocProperties.Sink.Artifact.Column(mask.name(), mask.from(),
                mask.value(), mask.type(), IndicatorType.DOMAIN, mask.transform(),
                List.of(IndicatorType.DOMAIN, IndicatorType.DOMAIN), List.of("unknown-condition"));

        contextRunner(withMasksColumnAt(source, 1, invalid))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "cannot combine when-type with when-types",
                        "when-types must be a nonempty list",
                        "unknown-condition"));
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
    void rejectsInvalidArtifactWritePolicyBeforeRuntimeAssembly() throws Exception {
        IocProperties source = defaults();
        IocProperties.Sink.Artifact masks = source.sink().artifacts().getFirst();
        var invalidPolicy = new IocProperties.Sink.Artifact.WritePolicy(
                "last-nonempty", "mask", List.of(
                new IocProperties.Sink.Artifact.WritePolicy.Field(
                        "mask", "latest-registered", "keep-existing")));
        var invalid = new IocProperties.Sink.Artifact(
                masks.name(), masks.enabled(), masks.path(), masks.accepts(), masks.include(),
                masks.exclude(), masks.id(), masks.columns(), invalidPolicy);

        contextRunner(withMasksArtifact(source, invalid))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "selection-column cannot be an identity",
                        "fields[0].name cannot be an identity"));
    }

    @Test
    void rejectsAmbiguousSourceLabelBinding() throws Exception {
        IocProperties source = defaults();
        IocProperties.Sink.Artifact masks = source.sink().artifacts().getFirst();
        List<IocProperties.Sink.Artifact.Column> columns = new ArrayList<>(masks.columns());
        columns.add(new IocProperties.Sink.Artifact.Column(
                "source_copy", "source.label", null, null, null, null));

        contextRunner(withMasksArtifact(source,
                copyArtifact(masks, masks.include(), masks.exclude(), columns)))
                .run(context -> assertRegistryFailure(context.getStartupFailure(),
                        "multiple source.label bindings"));
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

    private IocProperties withMasksArtifact(IocProperties source,
                                             IocProperties.Sink.Artifact replacement) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        artifacts.set(0, replacement);
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
                source.artifactIdentity(), source.dataframeImport(), source.export(), source.sync(), source.maintenance(),
                source.lifecycle(), source.observability(), source.processing());
    }

    private IocProperties withSink(IocProperties source, IocProperties.Sink sink) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), sink, source.pipeline(), source.ingestion(),
                source.artifactIdentity(), source.dataframeImport(), source.export(), source.sync(), source.maintenance(),
                source.lifecycle(), source.observability(), source.processing());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ConfigPreflightConfiguration.class)
    static class TestConfig {
    }
}
