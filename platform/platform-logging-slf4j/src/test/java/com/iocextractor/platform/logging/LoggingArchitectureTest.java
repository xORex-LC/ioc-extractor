package com.iocextractor.platform.logging;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("architecture")
class LoggingArchitectureTest {
    @Test
    void mainCodeDependsOnlyOnJdkSlf4jAndItsOwnTypes() {
        var main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.iocextractor.platform.logging");
        classes().should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", "org.slf4j..", "com.iocextractor.platform.logging..")
                .check(main);
    }

    @Test
    void exportedTypesMatchTheAdmittedSurface() {
        var main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.iocextractor.platform.logging");
        assertThat(main.stream().filter(type -> type.getModifiers().contains(
                        com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)))
                .extracting(com.tngtech.archunit.core.domain.JavaClass::getName).containsExactlyInAnyOrder(
                        "com.iocextractor.platform.logging.LogFieldSpec",
                        "com.iocextractor.platform.logging.LogValueType",
                        "com.iocextractor.platform.logging.MdcScope",
                        "com.iocextractor.platform.logging.LogEvent",
                        "com.iocextractor.platform.logging.LogEvents",
                        "com.iocextractor.platform.logging.ecs.EcsFields",
                        "com.iocextractor.platform.logging.ecs.EventOutcome",
                        "com.iocextractor.platform.logging.redaction.LogRedaction");
    }
}
