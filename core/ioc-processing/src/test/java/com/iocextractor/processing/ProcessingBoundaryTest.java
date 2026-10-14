package com.iocextractor.processing;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Enforces the pure IOC preparation boundary independently of bootstrap. */
@AnalyzeClasses(packages = "com.iocextractor.processing")
class ProcessingBoundaryTest {
    @ArchTest
    static final ArchRule no_outward_or_framework_dependencies = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapter..", "..bootstrap..",
                    "org.springframework..", "org.apache.camel..", "org.apache.commons.csv..",
                    "java.sql..", "javax.sql..");
}
