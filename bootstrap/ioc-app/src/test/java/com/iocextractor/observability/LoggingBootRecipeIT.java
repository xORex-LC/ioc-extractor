package com.iocextractor.observability;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

/** Executes the documented Boot recipe with the library and third-party JARs only. */
@IntegrationTest
@ContractTest
@Timeout(45)
class LoggingBootRecipeIT {
    @TempDir Path directory;

    @Test
    void documented_recipe_writes_typed_nested_records_and_restores_source_events() throws Exception {
        runRecipe("supported");

        assertThat(Files.readString(directory.resolve("status.txt"))).isEmpty();
        var mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        var lines = Files.readAllLines(directory.resolve("collector.ecs.json"));
        assertThat(lines).hasSize(5);
        var records = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var line : lines) {
            records.add(mapper.readTree(line));
        }
        assertThat(records).allSatisfy(record -> {
            assertThat(record.at("/service/name").textValue()).isEqualTo("feeds-collector");
            assertThat(record.at("/service/version").textValue()).isEqualTo("example-1");
            assertThat(record.at("/service/environment").textValue()).isEqualTo("example");
            assertThat(record.at("/event/dataset").textValue()).isEqualTo("feeds-collector");
            assertThat(record.at("/ecs/version").textValue()).isEqualTo("8.11");
            assertThat(record.at("/@timestamp").isTextual()).isTrue();
            assertThat(record.at("/log/logger").textValue()).isEqualTo("collector.jobs");
            assertThat(record.has("ioc")).isFalse();
        });
        var collection = records.get(0);
        assertThat(collection.at("/event/action").textValue()).isEqualTo("collect_complete");
        assertThat(collection.at("/event/outcome").textValue()).isEqualTo("success");
        assertThat(collection.at("/event/duration").isIntegralNumber()).isTrue();
        assertThat(collection.at("/event/duration").longValue()).isEqualTo(500L);
        assertThat(collection.at("/collector/items").isIntegralNumber()).isTrue();
        assertThat(collection.at("/collector/items").longValue()).isEqualTo(12L);
        assertThat(collection.at("/collector/retry").isBoolean()).isTrue();
        assertThat(collection.at("/collector/retry").booleanValue()).isFalse();
        assertThat(collection.at("/collector/task/id").textValue()).isEqualTo("task-42");
        assertThat(collection.at("/collector/auth/token").textValue()).isEqualTo("[redacted]");
        var omission = records.get(1);
        assertThat(omission.at("/event/action").textValue()).isEqualTo("collect_skipped");
        assertThat(omission.at("/event/outcome").isMissingNode()).isTrue();
        assertThat(omission.at("/event/duration").isMissingNode()).isTrue();
        assertThat(omission.at("/collector/auth/token").isMissingNode()).isTrue();
        var failure = records.get(2);
        assertThat(failure.at("/error/type").textValue()).isEqualTo(IllegalStateException.class.getName());
        assertThat(failure.at("/error/stack_trace").textValue()).contains("synthetic connection failure");
        assertThat(failure.at("/collector/request/url").textValue())
                .isEqualTo("https://<redacted>@example.test/p?<redacted>#f");
        assertThat(records.get(3).at("/error/type").textValue()).isEqualTo("collector.RemoteUnavailable");
        assertThat(records.get(3).at("/error/stack_trace").isMissingNode()).isTrue();
        assertThat(records.get(4).at("/event/action").textValue()).isEqualTo("collect_direct");
        assertThat(String.join("\n", lines)).doesNotContain("synthetic-ambient", "ambient-string");
    }

    @ParameterizedTest
    @CsvSource({
            "dataset-event, event.dataset belongs to application configuration",
            "dataset-mdc, event.dataset belongs to application configuration",
            "snapshot, This recipe requires a mutable LoggingEvent",
            "cause-collision, The name 'error' has already been written",
            "path-collision, collector",
            "reserved-metadata, service"
    })
    void unsupported_inputs_are_observable_appender_failures(String scenario, String message) throws Exception {
        runRecipe(scenario);

        assertThat(Files.readAllBytes(directory.resolve("collector.ecs.json"))).isEmpty();
        assertThat(Files.readString(directory.resolve("status.txt"))).contains("ERROR", message);
    }

    private void runRecipe(String scenario) throws Exception {
        var root = Path.of("../..").toAbsolutePath().normalize();
        String plainGuide = Files.readString(root.resolve("docs/guides/logging-library.md"));
        String bootGuide = Files.readString(root.resolve("docs/guides/logging-library-boot.md"));
        var sources = new ArrayList<Path>();
        sources.addAll(javaSources(plainGuide));
        sources.addAll(javaSources(bootGuide));
        var driver = directory.resolve("BootRecipeMain.java");
        Files.copy(Path.of("src/test/resources/consumer/logging/BootRecipeMain.java"), driver);
        sources.add(driver);
        Files.writeString(directory.resolve("application.yml"), block(bootGuide, "yaml"));
        Files.writeString(directory.resolve("logback-spring.xml"), block(bootGuide, "xml"));
        var classpath = new ArrayList<String>();
        classpath.add(root.resolve("platform/platform-logging-slf4j/target/classes").toString());
        Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> entry.endsWith(".jar") && !Path.of(entry).toAbsolutePath().normalize().startsWith(root))
                .forEach(classpath::add);
        String dependencies = String.join(java.io.File.pathSeparator, classpath);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("The documented consumer requires a full JDK").isNotNull();
        var diagnostics = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(diagnostics, manager, null,
                    List.of("--release", "21", "-proc:none", "-classpath", dependencies,
                            "-d", directory.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call();
            assertThat(compiled).as(diagnostics.toString()).isTrue();
        }
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", directory + java.io.File.pathSeparator + dependencies,
                "qualification.BootRecipeMain", directory.toString(), scenario);
        builder.environment().put("COLLECTOR_LOG_FILE", directory.resolve("collector.ecs.json").toString());
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile());
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Boot recipe JVM terminates").isTrue();
            assertThat(process.exitValue()).as(Files.readString(directory.resolve("process.log"))).isZero();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("Boot recipe JVM cleanup").isTrue();
        }
    }

    private List<Path> javaSources(String document) throws Exception {
        var blocks = Pattern.compile("```java\\R(.*?)\\R```", Pattern.DOTALL).matcher(document);
        var paths = new ArrayList<Path>();
        while (blocks.find()) {
            var name = Pattern.compile("public final class (\\w+)").matcher(blocks.group(1));
            assertThat(name.find()).as("Complete Java example in the guide").isTrue();
            var path = directory.resolve(name.group(1) + ".java");
            Files.writeString(path, blocks.group(1));
            paths.add(path);
        }
        return paths;
    }

    private String block(String document, String language) {
        var blocks = Pattern.compile("```" + language + "\\R(.*?)\\R```", Pattern.DOTALL).matcher(document);
        assertThat(blocks.find()).as("Executable " + language + " recipe block").isTrue();
        String content = blocks.group(1);
        assertThat(blocks.find()).as("Only one " + language + " configuration").isFalse();
        return content;
    }
}
