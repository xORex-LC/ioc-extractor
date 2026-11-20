package com.iocextractor.bootstrap;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Compiles the closed-input guide and exercises it without IOC classes on its classpath. */
@IntegrationTest
@ContractTest
@Timeout(45)
class ConfigurationCheckRecipeIT {
    @TempDir Path directory;

    @Test
    void documentedCheckUsesOnlyExplicitInputsAndDoesNotScanRuntimeComponents() throws Exception {
        Path root = Path.of("../..").toAbsolutePath().normalize();
        String guide = Files.readString(root.resolve("docs/guides/configuration-library-check.md"));
        Path source = directory.resolve("CollectorConfigurationCheck.java");
        Files.writeString(source, block(guide, "java"));
        Path driver = directory.resolve("ConfigurationRecipeMain.java");
        Files.copy(Path.of("src/test/resources/consumer/configuration/ConfigurationRecipeMain.java"), driver);
        Files.writeString(directory.resolve("collector.yml"), block(guide, "yaml"));
        // A Boot startup would discover this classpath file. The closed-input recipe must not.
        Files.writeString(directory.resolve("application.yml"), "spring.config.import: missing:ambient-secret\n");
        var entries = new ArrayList<String>();
        entries.add(root.resolve("adapters/adapter-config-spring-boot/target/classes").toString());
        Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> entry.endsWith(".jar") && !Path.of(entry).toAbsolutePath().normalize().startsWith(root))
                .forEach(entries::add);
        String dependencies = String.join(java.io.File.pathSeparator, entries);
        var diagnostics = new StringWriter();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("The documented check requires JDK 21").isNotNull();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(diagnostics, manager, null,
                    List.of("--release", "21", "-proc:none", "-classpath", dependencies, "-d", directory.toString()),
                    null, manager.getJavaFileObjectsFromPaths(List.of(source, driver))).call();
            assertThat(compiled).as(diagnostics.toString()).isTrue();
        }
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dcollector.request-timeout=invalid-ambient-secret", "-Dspring.main.sources=ambient-secret",
                "-cp", directory + java.io.File.pathSeparator + dependencies,
                "examples.configuration.ConfigurationRecipeMain", directory.toString());
        builder.environment().put("COLLECTOR_REQUESTTIMEOUT", "invalid-ambient-secret");
        builder.environment().put("SPRING_APPLICATION_JSON", "{\"collector\":{\"request-timeout\":\"ambient-secret\"}}");
        builder.directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("process.log").toFile());
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Closed-input check JVM terminates").isTrue();
            assertThat(process.exitValue()).as(Files.readString(directory.resolve("process.log"))).isZero();
            assertThat(Files.readString(directory.resolve("process.log"))).contains("RECIPE_VALIDATED")
                    .doesNotContain("runtime-component-created");
            assertThat(directory.resolve("runtime-marker")).doesNotExist();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("Closed-input check JVM cleanup").isTrue();
        }
    }

    private String block(String document, String language) {
        var blocks = Pattern.compile("```" + language + "\\R(.*?)\\R```", Pattern.DOTALL).matcher(document);
        assertThat(blocks.find()).as("Executable " + language + " guide block").isTrue();
        String result = blocks.group(1);
        assertThat(blocks.find()).as("Only one " + language + " guide block").isFalse();
        return result;
    }
}
