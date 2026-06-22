package com.iocextractor.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module-local guard for the intra-domain capability DAG.
 *
 * <p>The IOC domain is a single Maven module ({@code ioc-domain}); its
 * capability packages are kept in their lanes by these rules rather than by
 * separate artifacts (see {@code docs/dev/0009-modularization-granularity.md}).
 *
 * <p>Allowed edges (acyclic):
 * <pre>
 *   model    : leaf (depends on nothing)
 *   refang   : island (depends on nothing)
 *   extract  -> model
 *   feature  -> model
 *   classify -> feature, model
 *   attribute-> extract, model
 * </pre>
 */
@AnalyzeClasses(packages = "com.iocextractor.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundaryTest {

    @ArchTest
    static final ArchRule capabilities_are_acyclic = slices()
            .matching("com.iocextractor.domain.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule model_is_a_leaf = noClasses()
            .that().resideInAPackage("..domain.model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain.refang..", "..domain.extract..", "..domain.feature..",
                    "..domain.classify..", "..domain.attribute..");

    @ArchTest
    static final ArchRule refang_is_an_island = noClasses()
            .that().resideInAPackage("..domain.refang..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain.model..", "..domain.extract..", "..domain.feature..",
                    "..domain.classify..", "..domain.attribute..");

    @ArchTest
    static final ArchRule extract_depends_only_on_model = noClasses()
            .that().resideInAPackage("..domain.extract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain.refang..", "..domain.feature..",
                    "..domain.classify..", "..domain.attribute..");

    @ArchTest
    static final ArchRule feature_depends_only_on_model = noClasses()
            .that().resideInAPackage("..domain.feature..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain.refang..", "..domain.extract..",
                    "..domain.classify..", "..domain.attribute..");

    @ArchTest
    static final ArchRule classify_uses_feature_and_model_only = noClasses()
            .that().resideInAPackage("..domain.classify..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain.refang..", "..domain.extract..", "..domain.attribute..");

    @ArchTest
    static final ArchRule attribute_uses_extract_and_model_only = noClasses()
            .that().resideInAPackage("..domain.attribute..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain.refang..", "..domain.feature..", "..domain.classify..");
}
