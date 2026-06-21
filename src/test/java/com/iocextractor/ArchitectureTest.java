package com.iocextractor;

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
                    "info.picocli..", "com.google.common..");

    @ArchTest
    static final ArchRule application_depends_only_inward = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..bootstrap..", "org.springframework..");

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
