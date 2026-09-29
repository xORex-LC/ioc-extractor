package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog.PredicateRegistration;
import com.iocextractor.adapter.processing.camel.compile.RouteProtocol;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.domain.classify.FeaturePredicate;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.feature.NetworkHostDeriver;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/** Binds shared IOC operations and CSV preparers to Router's neutral catalog. */
final class IocProcessingOperations {
    private static final String NETWORK_HOST = "network.host";
    private final Map<String, ProcessingPlanCatalog.CompiledPlan> plans;
    private final Map<String, CsvArtifactPreparer> preparers;
    private final IndicatorClassifier classifier;

    IocProcessingOperations(ProcessingPlanCatalog.CompiledPlan plan,
                                 Map<String, CsvArtifactPreparer> preparers,
                                 IndicatorClassifier classifier) {
        this(Map.of(plan.router().id(), plan), preparers, classifier);
    }

    IocProcessingOperations(Map<String, ProcessingPlanCatalog.CompiledPlan> plans,
                            Map<String, CsvArtifactPreparer> preparers,
                            IndicatorClassifier classifier) {
        this.plans = Map.copyOf(plans);
        this.preparers = Map.copyOf(preparers);
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    OperationCatalog catalog() {
        var deriver = new NetworkHostDeriver(new NetworkAddressParser());
        Processor host = exchange -> {
            ProcessingView input = Objects.requireNonNull(
                    exchange.getMessage().getBody(ProcessingView.class), "processing view");
            var result = deriver.derive(input.classified().indicator());
            exchange.getMessage().setBody(result.isAvailable()
                    ? new ViewOutcome.Available(input.derived(new ClassifiedIndicator(
                            result.indicator(), classifier.classify(result.indicator()))))
                    : new ViewOutcome.Unavailable(new FailureReference(NETWORK_HOST,
                            result.failure().name().toLowerCase(java.util.Locale.ROOT)
                                    .replace('_', '-'))));
        };
        Map<String, Processor> destinations = new HashMap<>();
        preparers.forEach((artifact, preparer) -> destinations.put(artifact,
                exchange -> prepareBranch(exchange, artifact)));
        Map<String, PredicateRegistration> predicates = predicates();
        return new OperationCatalog(Map.of(NETWORK_HOST, host), destinations, predicates,
                java.util.Arrays.stream(NetworkAddressParser.FailureReason.values())
                        .map(reason -> reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static Map<String, PredicateRegistration> predicates() {
        Map<String, PredicateRegistration> registrations = new HashMap<>();
        for (Map.Entry<String, FeaturePredicate> entry : ConfigRegistryCatalog.featurePredicates().entrySet()) {
            registrations.put(entry.getKey(), new PredicateRegistration(Set.of(),
                    (value, args) -> entry.getValue().test(
                            ((ProcessingView) value).classified().classification().features())));
        }
        registrations.put("type-in", new PredicateRegistration(Set.of("types"),
                (value, args) -> java.util.Arrays.asList(args.get("types").split(","))
                        .contains(((ProcessingView) value).classified().indicator().type().name())));
        return registrations;
    }

    private void prepareBranch(Exchange exchange, String artifact) {
        String planId = exchange.getMessage().getHeader(RouteProtocol.PLAN_ID, String.class);
        String branchId = exchange.getMessage().getHeader(RouteProtocol.BRANCH_ID, String.class);
        ProcessingPlanCatalog.CompiledPlan plan = Objects.requireNonNull(
                plans.get(planId), "plan binding " + planId);
        ProcessingPlanCatalog.BranchBinding binding = Objects.requireNonNull(
                plan.bindings().get(branchId), "branch binding " + branchId);
        PlanExecutionResult.BranchInput input = Objects.requireNonNull(exchange.getMessage()
                .getBody(PlanExecutionResult.BranchInput.class), "branch input");
        ProcessingView selected = resolved(input, binding.defaultView());
        CsvArtifactPreparer preparer = selected.preparers().getOrDefault(artifact,
                Objects.requireNonNull(preparers.get(artifact), "artifact preparer " + artifact));
        Map<String, ClassifiedIndicator> columnViews = new HashMap<>();
        binding.fieldViews().forEach((column, view) ->
                columnViews.put(column, resolved(input, view).classified()));
        var prepared = preparer.prepareRouted(selected.classified(), columnViews,
                selected.position(), selected.ordinal());
        BranchOutcome outcome;
        if (!prepared.diagnostics().isEmpty()) {
            outcome = new BranchOutcome.Unavailable(
                    new FailureReference(branchId, "ROW_MAPPING_FAILED"), prepared.diagnostics().getFirst());
        } else if (prepared.value().isPresent()) {
            outcome = new BranchOutcome.Prepared(new RoutedArtifactCandidate(
                    binding.artifact(), prepared.value().orElseThrow()));
        } else {
            outcome = new BranchOutcome.Filtered();
        }
        exchange.getMessage().setBody(outcome);
    }

    private static ProcessingView resolved(PlanExecutionResult.BranchInput input, String view) {
        ViewOutcome outcome = Objects.requireNonNull(input.resolvedViews().get(view),
                "Required branch view was not available: " + view);
        return (ProcessingView) ((ViewOutcome.Available) outcome).value();
    }
}
