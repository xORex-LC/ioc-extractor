package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.domain.model.IndicatorType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingPlanCatalogTest {

    @Test
    void binds_nested_negation_and_groups_with_typed_arguments() {
        var properties = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                Map.of("condition.not.any[0].not.on", "original",
                        "condition.not.any[0].not.predicate", "has-path",
                        "condition.not.any[1].on", "original",
                        "condition.not.any[1].predicate", "type-in",
                        "condition.not.any[1].arguments.types[0]", "domain",
                        "condition.not.any[1].arguments.types[1]", "url"));
        var bound = new Binder(properties).bind("condition",
                Bindable.of(IocProcessingProperties.Condition.class)).get();

        assertThat(bound.not().any()).hasSize(2);
        assertThat(bound.not().any().getFirst().not().predicate()).isEqualTo("has-path");
        assertThat(bound.not().any().get(1).arguments().types())
                .containsExactly(IndicatorType.DOMAIN, IndicatorType.URL);
    }

    @Test
    void binds_negation_from_a_property_source_with_structured_lists() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                Map.of("condition.not.all", List.of(
                        Map.of("on", "original", "predicate", "type-in", "arguments",
                                Map.of("types", List.of("domain", "url"))),
                        Map.of("not", Map.of("on", "original", "predicate", "has-query")))));

        var condition = new Binder(source).bind("condition",
                Bindable.of(IocProcessingProperties.Condition.class)).get();

        assertThat(condition.not().all()).hasSize(2);
        assertThat(condition.not().all().getFirst().arguments().types())
                .containsExactly(IndicatorType.DOMAIN, IndicatorType.URL);
        assertThat(condition.not().all().get(1).not().predicate()).isEqualTo("has-query");
    }

    @Test
    void rejects_unknown_property_inside_negation_instead_of_silently_dropping_it() {
        var properties = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                Map.of("condition.not.on", "original", "condition.not.predicate", "has-path",
                        "condition.not.typo", "value"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Binder(properties)
                .bind("condition", Bindable.of(IocProcessingProperties.Condition.class)))
                .hasRootCauseInstanceOf(org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException.class);
    }

    @Test
    void compiles_typed_conditions_and_field_views_for_all_enabled_artifacts() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan plan = completePlan(defaults);
        List<String> errors = new ArrayList<>();

        var compiled = ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(plan))), errors);

        assertThat(errors).isEmpty();
        var network = compiled.get("network");
        assertThat(network.router().routing().branches())
                .hasSize((int) defaults.sink().artifacts().stream().filter(IocProperties.Sink.Artifact::enabled).count());
        assertThat(network.bindings().get("masks-branch").fieldViews()).containsEntry("mask", "host");
        assertThat(network.router().routing().branches().getFirst().requiredViews())
                .containsExactly("original", "host");
        assertThat(network.router().routing().branches().getFirst().eligibility())
                .isEqualTo(new Condition.Leaf("original", "type-in", Map.of("types", "DOMAIN,URL")));
    }

    @Test
    void reports_omission_and_illegal_field_binding_before_route_start() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var badBranch = new IocProcessingProperties.Branch("masks-branch", "masks", "original",
                null, Map.of("id", "host"));
        var plan = new IocProcessingProperties.Plan("network", valid.views(), valid.classifications(),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.ALL,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.SKIP, null),
                        List.of(badBranch), null), List.of());
        List<String> errors = new ArrayList<>();

        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(plan))), errors);

        assertThat(errors).anySatisfy(error -> assertThat(error).contains("field-views.id"));
        assertThat(errors).anySatisfy(error -> assertThat(error).contains("document-plan must reference a valid"));
    }

    @Test
    void rejects_ambiguous_condition_shape_and_duplicate_typed_arguments() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var malformed = new IocProcessingProperties.Condition("original", "type-in",
                new IocProcessingProperties.PredicateArguments(List.of(
                        IndicatorType.DOMAIN, IndicatorType.DOMAIN)), List.of(), null, null);
        var branch = new IocProcessingProperties.Branch("masks-branch", "masks", "original",
                malformed, Map.of());
        var plan = new IocProcessingProperties.Plan("network", valid.views(), valid.classifications(),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.ALL,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.SKIP, null),
                        List.of(branch), null), List.of());
        List<String> errors = new ArrayList<>();

        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(plan))), errors);

        assertThat(errors).anySatisfy(error -> assertThat(error).contains("exactly one leaf"));
    }

    @Test
    void requires_explicit_omission_for_each_enabled_unrouted_document_artifact() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        List<IocProcessingProperties.Branch> branches = valid.routing().branches();
        String omitted = branches.getLast().artifact();
        var routing = new IocProcessingProperties.Routing(valid.routing().mode(),
                valid.routing().onUnmatched(), branches.subList(0, branches.size() - 1), null);
        List<String> errors = new ArrayList<>();
        var missing = new IocProcessingProperties.Plan("network", valid.views(), valid.classifications(),
                routing, List.of());

        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(missing))), errors);

        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("omits enabled artifact", omitted));
        errors.clear();
        var acknowledged = new IocProcessingProperties.Plan("network", valid.views(),
                valid.classifications(), routing, List.of(omitted));
        assertThat(ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(acknowledged))), errors))
                .containsKey("network");
        assertThat(errors).isEmpty();
    }

    @Test
    void rejects_duplicate_type_in_arguments_without_expanding_one_input() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var duplicate = new IocProcessingProperties.Condition("original", "type-in",
                new IocProcessingProperties.PredicateArguments(List.of(
                        IndicatorType.DOMAIN, IndicatorType.DOMAIN)), null, null, null);
        var first = valid.routing().branches().getFirst();
        var replacement = new IocProcessingProperties.Branch(first.id(), first.artifact(),
                first.defaultView(), duplicate, first.fieldViews());
        List<IocProcessingProperties.Branch> branches = new ArrayList<>(valid.routing().branches());
        branches.set(0, replacement);
        var routing = new IocProcessingProperties.Routing(valid.routing().mode(),
                valid.routing().onUnmatched(), branches, null);
        var plan = new IocProcessingProperties.Plan("network", valid.views(), valid.classifications(),
                routing, List.of());
        List<String> errors = new ArrayList<>();

        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(plan))), errors);

        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("arguments.types requires distinct IOC types"));
    }

    @Test
    void compiles_explicit_recovery_as_a_separate_view_and_rejects_unknown_reasons() throws Exception {
        IocProperties defaults = defaults();
        var host = new IocProcessingProperties.View("host", "network.host", "original", null);
        var recovery = new IocProcessingProperties.View("usable", "view.recover", "host",
                new IocProcessingProperties.RecoveryArguments(
                        List.of("unsupported-address-form"), "original"));
        var route = new IocProcessingProperties.Routing(IocProcessingProperties.Mode.FIRST,
                new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.SKIP, null),
                List.of(new IocProcessingProperties.Branch("masks-usable", "masks", "usable",
                        null, Map.of())), null);
        var plan = new IocProcessingProperties.Plan("recovering", List.of(host, recovery),
                List.of(new IocProcessingProperties.Classification("usable", "configured")),
                route, List.of());
        List<String> errors = new ArrayList<>();

        var compiled = ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(plan))), errors);

        assertThat(errors).isEmpty();
        assertThat(compiled.get("recovering").router().views().get(1).recovery().alternateView())
                .isEqualTo("original");
        errors.clear();
        var invalidRecovery = new IocProcessingProperties.View("usable", "view.recover", "host",
                new IocProcessingProperties.RecoveryArguments(List.of("any-failure"), "original"));
        var invalid = new IocProcessingProperties.Plan("recovering", List.of(host, invalidRecovery),
                plan.classifications(), route, List.of());
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(invalid))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("unknown or empty recoverable reason"));
    }

    @Test
    void requires_classification_for_a_feature_predicate_view() throws Exception {
        IocProperties defaults = defaults();
        var condition = new IocProcessingProperties.Condition("host", "has-path",
                null, null, null, null);
        var branch = new IocProcessingProperties.Branch("hashes-host-condition", "hashes",
                "original", condition, Map.of());
        var plan = new IocProcessingProperties.Plan("feature-check",
                List.of(new IocProcessingProperties.View("host", "network.host", "original", null)),
                List.of(new IocProcessingProperties.Classification("original", "configured")),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.ALL,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.SKIP, null),
                        List.of(branch), null), List.of());
        List<String> errors = new ArrayList<>();

        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(plan))), errors);

        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("eligibility requires classification for view host"));
    }

    @Test
    void ungated_constant_override_is_admitted_without_demanding_its_view() throws Exception {
        IocProperties defaults = defaults();
        var plan = new IocProcessingProperties.Plan("constant", List.of(
                new IocProcessingProperties.View("host", "network.host", "original", null)),
                List.of(new IocProcessingProperties.Classification("original", "configured")),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.FIRST,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.SKIP, null),
                        List.of(new IocProcessingProperties.Branch("masks-constant", "masks",
                                "original", null, Map.of("description", "host"))), null), List.of());
        List<String> errors = new ArrayList<>();

        var compiled = ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(plan))), errors);

        assertThat(errors).isEmpty();
        assertThat(compiled.get("constant").router().routing().branches().getFirst().requiredViews())
                .containsExactly("original");
        assertThat(compiled.get("constant").bindings().get("masks-constant").fieldViews()).isEmpty();
    }

    @Test
    void preserves_ordered_boolean_condition_tree_in_router_descriptor() throws Exception {
        IocProperties defaults = defaults();
        var typeIn = new IocProcessingProperties.Condition("original", "type-in",
                new IocProcessingProperties.PredicateArguments(List.of(IndicatorType.DOMAIN)),
                null, null, null);
        var hasPath = new IocProcessingProperties.Condition("original", "has-path",
                null, null, null, null);
        var notIp = new IocProcessingProperties.Condition((String) null, null, null, null, null,
                new IocProcessingProperties.Condition("original", "type-in",
                        new IocProcessingProperties.PredicateArguments(List.of(IndicatorType.IPV4)),
                        null, null, null));
        var any = new IocProcessingProperties.Condition((String) null, null, null, null,
                List.of(hasPath, notIp), null);
        var all = new IocProcessingProperties.Condition((String) null, null, null,
                List.of(typeIn, any), null, null);
        IocProcessingProperties.Plan plan = withFirstEligibility(completePlan(defaults), all);
        List<String> errors = new ArrayList<>();

        var compiled = ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(plan))), errors);

        assertThat(errors).isEmpty();
        assertThat(compiled.get("network").router().routing().branches().getFirst().eligibility())
                .isEqualTo(new Condition.All(List.of(
                        new Condition.Leaf("original", "type-in", Map.of("types", "DOMAIN")),
                        new Condition.Any(List.of(
                                new Condition.Leaf("original", "has-path", Map.of()),
                                new Condition.Not(new Condition.Leaf("original", "type-in",
                                        Map.of("types", "IPV4"))))))));
    }

    @Test
    void rejects_malformed_groups_unknown_predicates_and_missing_typed_arguments() throws Exception {
        IocProperties defaults = defaults();
        List<IocProcessingProperties.Condition> invalid = List.of(
                new IocProcessingProperties.Condition((String) null, null, null, List.of(), null, null),
                new IocProcessingProperties.Condition((String) null, null, null, null, List.of(), null),
                new IocProcessingProperties.Condition("original", "not-registered", null,
                        null, null, null),
                new IocProcessingProperties.Condition("original", "type-in", null,
                        null, null, null),
                new IocProcessingProperties.Condition("missing-view", "has-path", null,
                        null, null, null));
        List<String> expected = List.of("boolean group cannot be empty", "boolean group cannot be empty",
                "unknown predicate", "arguments.types requires distinct", "declared on view");

        for (int index = 0; index < invalid.size(); index++) {
            String expectation = expected.get(index);
            var plan = withFirstEligibility(completePlan(defaults), invalid.get(index));
            List<String> errors = new ArrayList<>();
            ProcessingPlanCatalog.compile(withProcessing(defaults,
                    new IocProcessingProperties("network", List.of(plan))), errors);
            assertThat(errors).as("invalid condition case %s", index)
                    .anySatisfy(error -> assertThat(error).contains(expectation));
        }
    }

    @Test
    void admits_default_only_route_and_rejects_dangling_default_reference() throws Exception {
        IocProperties defaults = defaults();
        var fallback = new IocProcessingProperties.Branch("fallback", "masks", "original",
                null, Map.of());
        var routed = new IocProcessingProperties.Plan("default-only", List.of(), List.of(
                new IocProcessingProperties.Classification("original", "configured")),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.FIRST,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.ROUTE, "fallback"),
                        List.of(), fallback), List.of());
        List<String> errors = new ArrayList<>();

        var compiled = ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(routed))), errors);

        assertThat(errors).isEmpty();
        assertThat(compiled.get("default-only").router().routing().defaultBranch().id())
                .isEqualTo("fallback");
        errors.clear();
        var dangling = new IocProcessingProperties.Plan("default-only", routed.views(),
                routed.classifications(), new IocProcessingProperties.Routing(
                        IocProcessingProperties.Mode.FIRST,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.ROUTE,
                                "another-branch"), List.of(), fallback), List.of());
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(dangling))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("matching default branch"));
    }

    @Test
    void rejects_omitting_an_artifact_that_is_also_routed() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var conflicted = new IocProcessingProperties.Plan(valid.name(), valid.views(),
                valid.classifications(), valid.routing(), List.of("masks"));
        List<String> errors = new ArrayList<>();

        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(conflicted))), errors);

        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("omitted-artifacts has unknown, duplicate or routed artifact masks"));
    }

    @Test
    void rejects_duplicate_plan_names_and_unregistered_view_operations() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        List<String> errors = new ArrayList<>();
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(valid, valid))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error).contains("name duplicates network"));

        errors.clear();
        var invalidView = new IocProcessingProperties.View("host", "network.lookup", "original", null);
        var badOperation = new IocProcessingProperties.Plan("network", List.of(invalidView),
                valid.classifications(), valid.routing(), List.of());
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(badOperation))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("unknown operation or unexpected arguments"));
    }

    @Test
    void rejects_context_owned_and_unknown_field_views_and_disabled_destinations() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var first = valid.routing().branches().getFirst();
        var invalidField = new IocProcessingProperties.Branch(first.id(), first.artifact(),
                first.defaultView(), first.eligibility(), Map.of("source", "missing-view"));
        List<String> errors = new ArrayList<>();
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(withFirstBranch(valid, invalidField)))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error).contains("field-views.source"));
        assertThat(errors).anySatisfy(error -> assertThat(error).contains("references unknown view"));

        errors.clear();
        var unknownArtifact = new IocProcessingProperties.Branch(first.id(), "not-enabled",
                first.defaultView(), first.eligibility(), Map.of());
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(withFirstBranch(valid, unknownArtifact)))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("artifact must name an enabled artifact"));
    }

    @Test
    void rejects_missing_or_repeated_recovery_reasons() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        List<String> errors = new ArrayList<>();
        var missing = new IocProcessingProperties.View("usable", "view.recover", "host", null);
        var withoutArguments = new IocProcessingProperties.Plan("network",
                List.of(valid.views().getFirst(), missing), valid.classifications(),
                valid.routing(), List.of());
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(withoutArguments))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("arguments requires on-reasons and use-view"));

        errors.clear();
        var repeated = new IocProcessingProperties.View("usable", "view.recover", "host",
                new IocProcessingProperties.RecoveryArguments(List.of(
                        "invalid-host", "invalid-host"), "original"));
        var withDuplicates = new IocProcessingProperties.Plan("network",
                List.of(valid.views().getFirst(), repeated), valid.classifications(),
                valid.routing(), List.of());
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(withDuplicates))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("on-reasons contains duplicates"));
    }

    @Test
    void rejects_incomplete_plan_structure_at_each_required_boundary() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        assertRejected(defaults, new IocProcessingProperties.Plan("network", null,
                valid.classifications(), valid.routing(), null), ".views must be a list");
        assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                null, valid.routing(), null), ".classifications must be a list");
        assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                valid.classifications(), null, null), ".routing requires mode");
        assertRejected(defaults, withRouting(valid, new IocProcessingProperties.Routing(null,
                valid.routing().onUnmatched(), valid.routing().branches(), null)),
                ".routing requires mode");
        assertRejected(defaults, withRouting(valid, new IocProcessingProperties.Routing(
                IocProcessingProperties.Mode.ALL, null, valid.routing().branches(), null)),
                ".routing requires mode");
        assertRejected(defaults, withRouting(valid, new IocProcessingProperties.Routing(
                IocProcessingProperties.Mode.ALL,
                new IocProcessingProperties.OnUnmatched(null, null), valid.routing().branches(), null)),
                ".routing requires mode");
        assertRejected(defaults, withRouting(valid, new IocProcessingProperties.Routing(
                IocProcessingProperties.Mode.ALL, valid.routing().onUnmatched(), null, null)),
                ".routing requires mode");
    }

    @Test
    void rejects_malformed_plan_lists_and_bounds_plan_count() throws Exception {
        IocProperties defaults = defaults();
        List<String> errors = new ArrayList<>();
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, null)), errors);
        assertThat(errors).contains("ioc.processing.plans must be a list");

        errors.clear();
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, Collections.nCopies(33, completePlan(defaults)))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error).contains("plan limit"));

        errors.clear();
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, Arrays.asList(null,
                        new IocProcessingProperties.Plan(" ", null, null, null, null)))), errors);
        assertThat(errors).hasSize(2).allSatisfy(error -> assertThat(error).contains(".name is required"));
    }

    @Test
    void rejects_invalid_view_and_classification_bindings() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        for (IocProcessingProperties.View view : Arrays.asList(null,
                new IocProcessingProperties.View(" ", "network.host", "original", null),
                new IocProcessingProperties.View("host", " ", "original", null),
                new IocProcessingProperties.View("host", "network.host", " ", null))) {
            assertRejected(defaults, new IocProcessingProperties.Plan("network", Collections.singletonList(view),
                    valid.classifications(), valid.routing(), List.of()),
                    "requires name, operation and input");
        }
        for (IocProcessingProperties.Classification binding : Arrays.asList(null,
                new IocProcessingProperties.Classification("unseen", "configured"),
                new IocProcessingProperties.Classification("original", "other"))) {
            assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                    Arrays.asList(binding), valid.routing(), List.of()),
                    "requires a declared view and policy configured");
        }
        assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                List.of(valid.classifications().getFirst(), valid.classifications().getFirst()),
                valid.routing(), List.of()), "duplicates classification");
    }

    @Test
    void rejects_absent_and_ambiguous_condition_operands() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        List<IocProcessingProperties.Condition> invalid = List.of(
                new IocProcessingProperties.Condition((String) null, null, null, null, null, null),
                new IocProcessingProperties.Condition("original", null, null, null, null, null),
                new IocProcessingProperties.Condition((String) null, "has-path", null, null, null, null),
                new IocProcessingProperties.Condition("original", "has-path",
                        new IocProcessingProperties.PredicateArguments(List.of(IndicatorType.DOMAIN)),
                        null, null, null),
                new IocProcessingProperties.Condition("original", "type-in",
                        new IocProcessingProperties.PredicateArguments(List.of()), null, null, null),
                new IocProcessingProperties.Condition((String) null, null, null,
                        Arrays.asList((IocProcessingProperties.Condition) null), null, null));
        List<String> expected = List.of("exactly one leaf", "declared on view", "declared on view",
                "unexpected arguments", "arguments.types requires distinct", "cannot be null");
        for (int index = 0; index < invalid.size(); index++) {
            String message = expected.get(index);
            assertRejected(defaults, withFirstEligibility(valid, invalid.get(index)), message);
        }
    }

    @Test
    void rejects_missing_branch_identity_and_duplicate_destinations_ids() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        IocProcessingProperties.Branch first = valid.routing().branches().getFirst();
        for (IocProcessingProperties.Branch missing : Arrays.asList(null,
                new IocProcessingProperties.Branch(null, "masks", "original", null, Map.of()),
                new IocProcessingProperties.Branch(" ", "masks", "original", null, Map.of()),
                new IocProcessingProperties.Branch("branch", null, "original", null, Map.of()),
                new IocProcessingProperties.Branch("branch", "masks", null, null, Map.of()))) {
            assertRejected(defaults, withFirstBranch(valid, missing),
                    "requires id, artifact and default-view");
        }
        assertRejected(defaults, withFirstBranch(valid, new IocProcessingProperties.Branch(
                first.id(), first.artifact(), "unknown-view", first.eligibility(), Map.of())),
                ".default-view is unknown");

        List<IocProcessingProperties.Branch> duplicates = new ArrayList<>(valid.routing().branches());
        IocProcessingProperties.Branch second = duplicates.get(1);
        duplicates.set(1, new IocProcessingProperties.Branch(first.id(), second.artifact(),
                second.defaultView(), second.eligibility(), second.fieldViews()));
        assertRejected(defaults, withRouting(valid, new IocProcessingProperties.Routing(
                valid.routing().mode(), valid.routing().onUnmatched(), duplicates, null)),
                ".id duplicates " + first.id());
    }

    @Test
    void admits_absent_field_overrides_and_rejects_invalid_override_shapes() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        IocProcessingProperties.Branch first = valid.routing().branches().getFirst();
        var noOverrides = new IocProcessingProperties.Branch(first.id(), first.artifact(),
                first.defaultView(), first.eligibility(), null);
        List<String> errors = new ArrayList<>();
        var compiled = ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(withFirstBranch(valid, noOverrides)))), errors);
        assertThat(errors).isEmpty();
        assertThat(compiled.get("network").bindings().get(first.id()).fieldViews()).isEmpty();

        assertRejected(defaults, withFirstBranch(valid, new IocProcessingProperties.Branch(
                first.id(), first.artifact(), first.defaultView(), first.eligibility(),
                Map.of("missing-column", "host"))), "field-views.missing-column");
        assertRejected(defaults, withFirstBranch(valid, new IocProcessingProperties.Branch(
                first.id(), first.artifact(), first.defaultView(), first.eligibility(),
                Map.of("id", "host"))), "field-views.id");
        Map<String, String> emptyView = new java.util.HashMap<>();
        emptyView.put("mask", null);
        assertRejected(defaults, withFirstBranch(valid, new IocProcessingProperties.Branch(
                first.id(), first.artifact(), first.defaultView(), first.eligibility(), emptyView)),
                "field-views.mask references unknown view");
    }

    @Test
    void rejects_default_eligibility_and_invalid_omission_catalogs() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var fallback = new IocProcessingProperties.Branch("fallback", "masks", "original",
                valid.routing().branches().getFirst().eligibility(), Map.of());
        assertRejected(defaults, withRouting(valid, new IocProcessingProperties.Routing(
                IocProcessingProperties.Mode.ALL,
                new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.ROUTE, "fallback"),
                valid.routing().branches(), fallback)), "default-branch cannot have eligibility");
        assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                valid.classifications(), valid.routing(), List.of("not-enabled")),
                "omitted-artifacts has unknown");
        assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                valid.classifications(), valid.routing(), Collections.singletonList(null)),
                "omitted-artifacts has unknown");

        List<String> errors = new ArrayList<>();
        var withoutOmissions = new IocProcessingProperties.Plan("network", valid.views(),
                valid.classifications(), valid.routing(), null);
        assertThat(ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties("network", List.of(withoutOmissions))), errors))
                .containsKey("network");
        assertThat(errors).isEmpty();
    }

    @Test
    void rejects_incomplete_recovery_and_unexpected_operation_arguments() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        var hostWithArgs = new IocProcessingProperties.View("host", "network.host", "original",
                new IocProcessingProperties.RecoveryArguments(List.of("invalid-host"), "original"));
        assertRejected(defaults, new IocProcessingProperties.Plan("network", List.of(hostWithArgs),
                valid.classifications(), valid.routing(), List.of()),
                "unknown operation or unexpected arguments");
        for (IocProcessingProperties.RecoveryArguments args : List.of(
                new IocProcessingProperties.RecoveryArguments(List.of(), "original"),
                new IocProcessingProperties.RecoveryArguments(List.of("invalid-host"), " "),
                new IocProcessingProperties.RecoveryArguments(
                        Collections.singletonList(null), "original"))) {
            var recovery = new IocProcessingProperties.View("usable", "view.recover", "host", args);
            assertRejected(defaults, new IocProcessingProperties.Plan("network",
                    List.of(valid.views().getFirst(), recovery), valid.classifications(),
                    valid.routing(), List.of()), "arguments requires on-reasons and use-view");
        }
    }

    @Test
    void handles_incomplete_artifact_catalog_without_accepting_a_broken_plan() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        for (IocProperties.Sink sink : Arrays.asList(null,
                new IocProperties.Sink(defaults.sink().csv(), null))) {
            List<String> errors = new ArrayList<>();
            ProcessingPlanCatalog.compile(withSinkAndProcessing(defaults, sink,
                    new IocProcessingProperties(null, List.of(valid))), errors);
            assertThat(errors).anySatisfy(error -> assertThat(error)
                    .contains("artifact must name an enabled artifact"));
        }

        IocProperties.Sink.Artifact masks = defaults.sink().artifacts().getFirst();
        List<List<IocProperties.Sink.Artifact>> incomplete = List.of(
                Arrays.asList(null, defaults.sink().artifacts().get(1)),
                List.of(new IocProperties.Sink.Artifact(masks.name(), false, masks.path(),
                        masks.accepts(), masks.include(), masks.exclude(), masks.id(), masks.columns())),
                List.of(new IocProperties.Sink.Artifact(null, true, masks.path(),
                        masks.accepts(), masks.include(), masks.exclude(), masks.id(), masks.columns())));
        for (List<IocProperties.Sink.Artifact> artifacts : incomplete) {
            List<String> errors = new ArrayList<>();
            ProcessingPlanCatalog.compile(withSinkAndProcessing(defaults,
                    new IocProperties.Sink(defaults.sink().csv(), artifacts),
                    new IocProcessingProperties(null, List.of(valid))), errors);
            assertThat(errors).anySatisfy(error -> assertThat(error)
                    .contains("artifact must name an enabled artifact"));
        }
        List<IocProperties.Sink.Artifact> duplicate = new ArrayList<>(defaults.sink().artifacts());
        duplicate.add(masks);
        List<String> errors = new ArrayList<>();
        ProcessingPlanCatalog.compile(withSinkAndProcessing(defaults,
                new IocProperties.Sink(defaults.sink().csv(), duplicate),
                new IocProcessingProperties(null, List.of(valid))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error)
                .contains("duplicate enabled name masks"));
    }

    @Test
    void admits_gated_constant_view_bindings_and_rejects_missing_column_schema() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        IocProperties.Sink.Artifact masks = defaults.sink().artifacts().getFirst();
        IocProcessingProperties.Branch first = valid.routing().branches().getFirst();
        var overridden = new IocProcessingProperties.Branch(first.id(), first.artifact(),
                first.defaultView(), first.eligibility(), Map.of("description", "host"));
        var plan = withFirstBranch(valid, overridden);
        List<IocProperties.Sink.Artifact.Column> gates = List.of(
                new IocProperties.Sink.Artifact.Column("description", "const", null, null,
                        IndicatorType.DOMAIN, null),
                new IocProperties.Sink.Artifact.Column("description", "const", null, null,
                        null, null, List.of(IndicatorType.DOMAIN), null),
                new IocProperties.Sink.Artifact.Column("description", "const", null, null,
                        null, null, null, List.of("has-path")));
        for (IocProperties.Sink.Artifact.Column gate : gates) {
            List<IocProperties.Sink.Artifact.Column> columns = new ArrayList<>(masks.columns());
            int descriptionIndex = java.util.stream.IntStream.range(0, columns.size())
                    .filter(index -> "description".equals(columns.get(index).name()))
                    .findFirst().orElseThrow();
            columns.set(descriptionIndex, gate);
            var changed = new IocProperties.Sink.Artifact(masks.name(), true, masks.path(),
                    masks.accepts(), masks.include(), masks.exclude(), masks.id(), columns);
            List<String> errors = new ArrayList<>();
            var compiled = ProcessingPlanCatalog.compile(withSinkAndProcessing(defaults,
                    replacingMasks(defaults, changed), new IocProcessingProperties(null, List.of(plan))), errors);
            assertThat(errors).isEmpty();
            assertThat(compiled.get("network").bindings().get(first.id()).fieldViews())
                    .containsEntry("description", "host");
        }
        var withoutColumns = new IocProperties.Sink.Artifact(masks.name(), true, masks.path(),
                masks.accepts(), masks.include(), masks.exclude(), masks.id(), null);
        List<String> errors = new ArrayList<>();
        ProcessingPlanCatalog.compile(withSinkAndProcessing(defaults,
                replacingMasks(defaults, withoutColumns),
                new IocProcessingProperties(null, List.of(plan))), errors);
        assertThat(errors).anySatisfy(error -> assertThat(error).contains("field-views.description"));
    }

    @Test
    void rejects_ambiguous_omission_and_missing_recovery_or_plan_name() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        List<IocProcessingProperties.Branch> branches = valid.routing().branches();
        String omitted = branches.getLast().artifact();
        var shortened = new IocProcessingProperties.Routing(valid.routing().mode(),
                valid.routing().onUnmatched(), branches.subList(0, branches.size() - 1), null);
        assertRejected(defaults, new IocProcessingProperties.Plan("network", valid.views(),
                valid.classifications(), shortened, List.of(omitted, omitted)),
                "omitted-artifacts has unknown, duplicate or routed");
        assertRejected(defaults, new IocProcessingProperties.Plan(null, valid.views(),
                valid.classifications(), valid.routing(), List.of()), ".name is required");

        var missingReasons = new IocProcessingProperties.View("usable", "view.recover", "host",
                new IocProcessingProperties.RecoveryArguments(null, "original"));
        assertRejected(defaults, new IocProcessingProperties.Plan("network",
                List.of(valid.views().getFirst(), missingReasons), valid.classifications(),
                valid.routing(), List.of()), "arguments requires on-reasons and use-view");
    }

    @Test
    void rejects_deep_or_partially_invalid_condition_trees() throws Exception {
        IocProperties defaults = defaults();
        IocProcessingProperties.Plan valid = completePlan(defaults);
        IocProcessingProperties.Condition deepest = new IocProcessingProperties.Condition(
                "original", "has-path", null, null, null, null);
        for (int index = 0; index < 17; index++) {
            deepest = new IocProcessingProperties.Condition((String) null, null, null, null, null, deepest);
        }
        assertRejected(defaults, withFirstEligibility(valid, deepest), "condition depth limit");
        var invalidChild = new IocProcessingProperties.Condition((String) null, null, null, null, null, null);
        assertRejected(defaults, withFirstEligibility(valid,
                new IocProcessingProperties.Condition((String) null, null, null, null, null, invalidChild)),
                "exactly one leaf");
        assertRejected(defaults, withFirstEligibility(valid,
                new IocProcessingProperties.Condition((String) null, null, null,
                        List.of(invalidChild), null, null)), "exactly one leaf");
        assertRejected(defaults, withFirstEligibility(valid,
                new IocProcessingProperties.Condition((String) null, null,
                        new IocProcessingProperties.PredicateArguments(null), null, null, null)),
                "declared on view");
        assertRejected(defaults, withFirstEligibility(valid,
                new IocProcessingProperties.Condition("original", "type-in",
                        new IocProcessingProperties.PredicateArguments(
                                Collections.singletonList(null)), null, null, null)),
                "arguments.types requires distinct");
        assertRejected(defaults, withFirstEligibility(valid,
                new IocProcessingProperties.Condition("original", " ", null, null, null, null)),
                "declared on view");
    }

    private static IocProcessingProperties.Plan withFirstEligibility(
            IocProcessingProperties.Plan source, IocProcessingProperties.Condition condition) {
        List<IocProcessingProperties.Branch> branches = new ArrayList<>(source.routing().branches());
        IocProcessingProperties.Branch first = branches.getFirst();
        branches.set(0, new IocProcessingProperties.Branch(first.id(), first.artifact(),
                first.defaultView(), condition, first.fieldViews()));
        return new IocProcessingProperties.Plan(source.name(), source.views(), source.classifications(),
                new IocProcessingProperties.Routing(source.routing().mode(), source.routing().onUnmatched(),
                        branches, source.routing().defaultBranch()), source.omittedArtifacts());
    }

    private static IocProcessingProperties.Plan withFirstBranch(
            IocProcessingProperties.Plan source, IocProcessingProperties.Branch replacement) {
        List<IocProcessingProperties.Branch> branches = new ArrayList<>(source.routing().branches());
        branches.set(0, replacement);
        return new IocProcessingProperties.Plan(source.name(), source.views(), source.classifications(),
                new IocProcessingProperties.Routing(source.routing().mode(), source.routing().onUnmatched(),
                        branches, source.routing().defaultBranch()), source.omittedArtifacts());
    }

    private static IocProcessingProperties.Plan withRouting(
            IocProcessingProperties.Plan source, IocProcessingProperties.Routing routing) {
        return new IocProcessingProperties.Plan(source.name(), source.views(),
                source.classifications(), routing, source.omittedArtifacts());
    }

    private static void assertRejected(IocProperties defaults, IocProcessingProperties.Plan plan,
                                       String expected) {
        List<String> errors = new ArrayList<>();
        ProcessingPlanCatalog.compile(withProcessing(defaults,
                new IocProcessingProperties(null, List.of(plan))), errors);
        assertThat(errors).as("invalid IOC plan: %s", expected)
                .anySatisfy(error -> assertThat(error).contains(expected));
    }

    private static IocProcessingProperties.Plan completePlan(IocProperties defaults) {
        var host = new IocProcessingProperties.View("host", "network.host", "original", null);
        var typeIn = new IocProcessingProperties.Condition("original", "type-in",
                new IocProcessingProperties.PredicateArguments(List.of(IndicatorType.DOMAIN, IndicatorType.URL)),
                null, null, null);
        List<IocProcessingProperties.Branch> branches = defaults.sink().artifacts().stream()
                .filter(IocProperties.Sink.Artifact::enabled)
                .map(artifact -> new IocProcessingProperties.Branch(artifact.name() + "-branch",
                        artifact.name(), "original", typeIn,
                        "masks".equals(artifact.name()) ? Map.of("mask", "host") : Map.of()))
                .toList();
        return new IocProcessingProperties.Plan("network", List.of(host), List.of(
                new IocProcessingProperties.Classification("original", "configured"),
                new IocProcessingProperties.Classification("host", "configured")),
                new IocProcessingProperties.Routing(IocProcessingProperties.Mode.ALL,
                        new IocProcessingProperties.OnUnmatched(IocProcessingProperties.Action.SKIP, null),
                        branches, null), List.of());
    }

    private static IocProperties withProcessing(IocProperties source, IocProcessingProperties processing) {
        return withSinkAndProcessing(source, source.sink(), processing);
    }

    private static IocProperties withSinkAndProcessing(IocProperties source, IocProperties.Sink sink,
                                                        IocProcessingProperties processing) {
        return new IocProperties(source.engine(), source.runtime(), source.storage(), source.source(),
                source.refang(), source.patterns(), source.classify(), sink, source.pipeline(),
                source.ingestion(), source.artifactIdentity(), source.dataframeImport(), source.export(),
                source.sync(), source.maintenance(), source.lifecycle(), source.observability(), processing);
    }

    private static IocProperties.Sink replacingMasks(IocProperties source,
                                                       IocProperties.Sink.Artifact replacement) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        artifacts.set(0, replacement);
        return new IocProperties.Sink(source.sink().csv(), artifacts);
    }

    private static IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        ApplicationConversionService conversions = new ApplicationConversionService();
        conversions.addConverter(String.class, IdStart.class, IdStart::parse);
        conversions.addConverter(Number.class, IdStart.class, IdStart::from);
        return new Binder(ConfigurationPropertySources.from(source), null, conversions)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default IOC configuration did not bind"));
    }
}
