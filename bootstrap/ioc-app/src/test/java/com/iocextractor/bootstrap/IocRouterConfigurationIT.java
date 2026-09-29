package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.time.Clock;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Selected IOC plans activate one Camel runtime; an unselected catalog does not. */
@IntegrationTest
@Timeout(20)
class IocRouterConfigurationIT {
    @TempDir Path tempDir;

    @Test
    void changedDocumentPolicyRequiresDrainedLedgerAndProcessingFiles() throws Exception {
        String old = "a".repeat(64);
        String changed = "b".repeat(64);
        DocumentProcessingPolicyAdmission.ensure(null, old, false,
                () -> { throw new AssertionError("no storage requires no drain check"); },
                () -> { throw new AssertionError("no storage requires no drain check"); });
        assertThatThrownBy(() -> DocumentProcessingPolicyAdmission.ensure(null, old, true,
                () -> true, () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires durable service storage");

        var settings = new IocProperties.Storage.Service(StorageType.JDBC,
                "jdbc:sqlite:" + tempDir.resolve("document-policy.db"),
                new IocProperties.Storage.Sqlite("low-memory"),
                new IocProperties.Storage.Pool(1, 1));
        try (var storage = new LazyServiceStorage(settings, NoopDiagnosticSink.INSTANCE, Clock.systemUTC())) {
            DocumentProcessingPolicyAdmission.ensure(storage, old, true, () -> true, () -> true);
            DocumentProcessingPolicyAdmission.ensure(storage, old, true,
                    () -> { throw new AssertionError("unchanged policy requires no drain check"); },
                    () -> { throw new AssertionError("unchanged policy requires no drain check"); });
            assertThatThrownBy(() -> DocumentProcessingPolicyAdmission.ensure(storage, changed, true,
                    () -> false,
                    () -> { throw new AssertionError("processing check follows ledger check"); }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unfinished intake");
            assertThatThrownBy(() -> DocumentProcessingPolicyAdmission.ensure(storage, changed, true,
                    () -> true, () -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unfinished intake");
            DocumentProcessingPolicyAdmission.ensure(storage, changed, true, () -> true, () -> true);
            try (var connection = storage.dataSource().getConnection();
                 var rows = connection.createStatement().executeQuery(
                         "SELECT policy_fingerprint FROM document_processing_policy WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(changed);
            }
        }
    }

    @Test
    void selected_plan_is_executable_and_unselected_catalog_stays_inert() {
        var descriptor = new PlanDescriptor("selected", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST,
                List.of(new PlanDescriptor.Branch("mask", "masks", null, List.of("original"))),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
        var compiled = new ProcessingPlanCatalog.CompiledPlan(descriptor,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding("masks", "original", Map.of())),
                Set.of("original"));
        var bindings = new ProcessingPlanBindings("selected", Map.of("selected", compiled));
        AppConfig assembly = new AppConfig();
        IocProperties properties = defaults();
        ArtifactIdBaseline baseline = artifact -> 0;
        MatchPolicy matchPolicy = indicator -> new ClassificationDecision(
                new IndicatorFeatures(indicator.value(), indicator.value(), false, false,
                        false, HostKind.REGISTRABLE), 0, List.of(), new MaskMatch("u:hAS", "h:dAS"));
        var registration = new IocRouterConfiguration().iocRouterPlanRegistration(
                bindings, assembly, properties, baseline,
                new CanonicalArtifactIdentityResolver(assembly.artifactIdentityDefinitions(properties)),
                NoopPipelineDecisionTracer.INSTANCE, matchPolicy, Clock.systemUTC(),
                new ProcessingPolicyIdentity("a".repeat(64)));
        new ApplicationContextRunner()
                .withUserConfiguration(IocRouterConfiguration.class, RouterRuntimeConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(CamelRouteRuntime.class));
        new ApplicationContextRunner()
                .withUserConfiguration(RouterRuntimeConfiguration.class)
                .withBean(RouterPlanRegistration.class, () -> registration)
                .withBean(com.iocextractor.application.port.out.observability.PipelineDecisionTracer.class,
                        () -> NoopPipelineDecisionTracer.INSTANCE)
                .run(context -> {
            assertThat(context).hasNotFailed();
            var indicator = new Indicator("example.com", IndicatorType.DOMAIN,
                    new SourceContext("feed", null));
            var original = new ProcessingView(new ClassifiedIndicator(indicator,
                    matchPolicy.classify(indicator)), new OccurrencePosition(1), 0);
            var outcome = context.getBean(CamelRouteRuntime.class).execute("selected", original);
            assertThat(outcome.replies()).hasSize(1);
        });
    }

    private static IocProperties defaults() {
        try {
            var source = new YamlPropertySourceLoader()
                    .load("defaults", new ClassPathResource("application.yml")).getFirst();
            var conversion = new ApplicationConversionService();
            conversion.addConverter(String.class, IdStart.class, IdStart::parse);
            conversion.addConverter(Number.class, IdStart.class, IdStart::from);
            return new Binder(ConfigurationPropertySources.from(source), null, conversion)
                    .bind("ioc", Bindable.of(IocProperties.class))
                    .orElseThrow(() -> new IllegalStateException("Default IOC configuration did not bind"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot load the shipped IOC configuration", failure);
        }
    }
}
