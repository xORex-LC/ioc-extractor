package com.iocextractor.adapter.processing.camel;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Guards the generic compiler/runtime seam against IOC and Spring leakage. */
@AnalyzeClasses(packages = "com.iocextractor.adapter.processing.camel",
        importOptions = ImportOption.DoNotIncludeTests.class)
@Tag("architecture")
class NeutralPackagesArchitectureTest {
    @ArchTest
    static final ArchRule neutral_packages_do_not_depend_on_ioc_or_bootstrap = noClasses()
            .that().resideInAnyPackage("..processing.camel.contract..",
                    "..processing.camel.compile..", "..processing.camel.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage("..domain..",
                    "..application..", "..bootstrap..", "..processing.camel.bridge..",
                    "org.springframework..");
}
