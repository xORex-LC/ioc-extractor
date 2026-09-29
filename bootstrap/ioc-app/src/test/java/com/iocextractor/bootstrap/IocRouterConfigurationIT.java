package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.application.tck.junit.IntegrationTest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

/** Selected IOC plans activate one Camel runtime; an unselected catalog does not. */
@IntegrationTest
@Timeout(20)
class IocRouterConfigurationIT {
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
