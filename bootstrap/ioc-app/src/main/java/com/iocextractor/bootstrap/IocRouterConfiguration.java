package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.processing.classification.IndicatorClassifier;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Admits configured IOC plans into the shared embedded Router runtime. */
@Configuration(proxyBeanMethods = false)
class IocRouterConfiguration {

    @Bean
    RouterPlanRegistration iocRouterPlanRegistration(
            ProcessingPlanBindings bindings,
            AppConfig appConfig,
            IocProperties properties,
            ArtifactIdBaseline baseline,
            PipelineDecisionTracer tracer,
            MatchPolicy matchPolicy,
            Clock clock,
            ProcessingPolicyIdentity policyIdentity) {
        Map<String, CsvArtifactPreparer> preparers = new LinkedHashMap<>();
        appConfig.artifactPreparers(appConfig.artifactDefinitions(properties, baseline),
                null, clock, tracer).forEach(preparer ->
                preparers.put(preparer.name(), (CsvArtifactPreparer) preparer));
        var catalog = new IocProcessingOperations(bindings.plans(), preparers,
                new IndicatorClassifier(matchPolicy)).catalog();
        return new RouterPlanRegistration(bindings.plans().values().stream()
                .map(ProcessingPlanCatalog.CompiledPlan::router).toList(),
                catalog, policyIdentity.value());
    }
}
