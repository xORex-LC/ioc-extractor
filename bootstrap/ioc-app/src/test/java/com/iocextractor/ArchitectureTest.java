package com.iocextractor;

import com.iocextractor.platform.etl.Stage;
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
    static final ArchRule platform_etl_is_generic_and_framework_free = noClasses()
            .that().resideInAPackage("..platform.etl..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain..", "..application..", "..adapter..", "..bootstrap..",
                    "..observability..", "org.springframework..", "org.slf4j..",
                    "ch.qos.logback..", "info.picocli..", "org.apache.tika..",
                    "org.apache.commons.csv..");

    @ArchTest
    static final ArchRule application_pipeline_is_framework_and_adapter_free = noClasses()
            .that().resideInAPackage("..application.pipeline..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.slf4j..", "ch.qos.logback..",
                    "info.picocli..", "org.apache.tika..", "org.apache.commons.csv..",
                    "..adapter..", "..bootstrap..");

    @ArchTest
    static final ArchRule storage_mechanism_types_stay_in_adapters_and_bootstrap = noClasses()
            .that().resideOutsideOfPackages("..adapter..", "..bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..",
                    "org.springframework.transaction..", "org.sqlite..", "com.zaxxer.hikari..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_application_pipeline = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application.pipeline..");

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_concrete_pipeline_stages = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat().resideInAnyPackage("..application.pipeline.stage..");

    @ArchTest
    static final ArchRule streaming_snapshot_reader_does_not_materialize_canonical_artifacts = noClasses()
            .that().haveSimpleName("JdbcSnapshotSliceReader")
            .should().dependOnClassesThat().haveSimpleName("CanonicalArtifact");

    @ArchTest
    static final ArchRule slice_writer_owns_no_jdbc_or_materialized_repository_model = noClasses()
            .that().resideInAPackage("..adapter.out.sink.csv..")
            .and().haveSimpleNameContaining("Slice")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..", "javax.sql..", "..adapter.out.store.jdbc..")
            .orShould().dependOnClassesThat().haveSimpleName("CanonicalArtifact");

    @ArchTest
    static final ArchRule jackson_manifest_mapping_stays_in_its_adapter = noClasses()
            .that().resideOutsideOfPackages(
                    "..adapter.out.manifest.json..",
                    "..adapter.in.cli..",
                    "..bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule concrete_pipeline_stages_do_not_own_pipeline_order = noClasses()
            .that().resideInAPackage("..application.pipeline.stage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..platform.etl.Pipeline",
                    "..platform.etl.PipelineRunner");

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

    // ING-6: source-key / partition-wrapper concerns stay inside ingest. Neither the
    // domain nor the generic extraction pipeline may depend on ingest packages, so a
    // SourceKey can only reach the core as opaque Envelope metadata.
    @ArchTest
    static final ArchRule domain_does_not_depend_on_ingest = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..ingest..");

    @ArchTest
    static final ArchRule extraction_pipeline_does_not_depend_on_ingest = noClasses()
            .that().resideInAPackage("..application.pipeline..")
            .should().dependOnClassesThat().resideInAnyPackage("..ingest..");

    @ArchTest
    static final ArchRule no_package_cycles = slices()
            .matching("com.iocextractor.(*)..")
            .should().beFreeOfCycles();
}
