package com.iocextractor;

import com.iocextractor.application.pipeline.Stage;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the architectural boundaries from docs/boundaries.md as failing
 * tests. Production classes only (tests excluded), so the rules guard the
 * shipped code, not the test scaffolding.
 */
@AnalyzeClasses(packages = "com.iocextractor", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers_point_inward = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("domain").definedBy("..domain..")
            .layer("application").definedBy("..application..")
            .layer("adapter").definedBy("..adapter..")
            .layer("bootstrap").definedBy("..bootstrap..")
            .whereLayer("bootstrap").mayNotBeAccessedByAnyLayer()
            .whereLayer("adapter").mayOnlyBeAccessedByLayers("bootstrap")
            .whereLayer("application").mayOnlyBeAccessedByLayers("adapter", "bootstrap")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "adapter", "bootstrap");

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.apache..", "com.google.re2j..",
                    "info.picocli..", "com.google.common..", "org.slf4j..",
                    "ch.qos.logback..");

    @ArchTest
    static final ArchRule application_depends_only_inward = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..bootstrap..", "org.springframework..",
                    "org.slf4j..", "ch.qos.logback..");

    @ArchTest
    static final ArchRule diagnostics_core_is_framework_and_adapter_free = noClasses()
            .that().resideInAPackage("com.iocextractor.diagnostics..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.slf4j..", "ch.qos.logback..",
                    "..adapter..", "..bootstrap..", "..application.service..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_diagnostics_delivery = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..diagnostics.sink..", "..diagnostics.render..");

    @ArchTest
    static final ArchRule pipeline_core_is_framework_and_adapter_free = noClasses()
            .that().resideInAPackage("..application.pipeline..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.slf4j..", "ch.qos.logback..",
                    "info.picocli..", "org.apache.tika..", "org.apache.commons.csv..",
                    "..adapter..", "..bootstrap..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_application_pipeline = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application.pipeline..");

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_concrete_pipeline_stages = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat().resideInAnyPackage("..application.pipeline.stage..");

    @ArchTest
    static final ArchRule concrete_pipeline_stages_do_not_own_pipeline_order = noClasses()
            .that().resideInAPackage("..application.pipeline.stage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application.pipeline.Pipeline",
                    "..application.pipeline.PipelineRunner");

    @ArchTest
    static final ArchRule concrete_pipeline_stages_implement_stage = classes()
            .that().resideInAPackage("..application.pipeline.stage..")
            .and().haveSimpleNameEndingWith("Stage")
            .should().implement(Stage.class);

    @ArchTest
    static final ArchRule logging_diagnostic_sink_is_outside_diagnostics_core = noClasses()
            .that().resideInAPackage("com.iocextractor.diagnostics..")
            .should().haveSimpleName("LoggingDiagnosticSink");

    @ArchTest
    static final ArchRule observability_does_not_depend_on_business_or_adapters = noClasses()
            .that().resideInAPackage("..observability..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain..", "..adapter..", "..bootstrap..");

    @ArchTest
    static final ArchRule ports_are_interfaces = classes()
            .that().resideInAPackage("..application.port..")
            .and().haveSimpleNameNotEndingWith("Command")
            .and().haveSimpleNameNotEndingWith("Result")
            .should().beInterfaces();

    @ArchTest
    static final ArchRule no_package_cycles = slices()
            .matching("com.iocextractor.(*)..")
            .should().beFreeOfCycles();
}
