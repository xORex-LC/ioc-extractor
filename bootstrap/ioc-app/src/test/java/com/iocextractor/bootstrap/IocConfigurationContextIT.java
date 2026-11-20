package com.iocextractor.bootstrap;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Qualifies effective Boot loading and the configuration-only graph in separate command JVMs. */
@IntegrationTest
@ContractTest
@Timeout(60)
class IocConfigurationContextIT {
    @TempDir Path directory;

    @Test
    void loadsImportsAndDistinguishesRequiredFromOptionalMissingLocations() throws Exception {
        Files.writeString(directory.resolve("imported.yml"), "ioc.ingestion.concurrency: 2\n");
        var invalid = check("spring.config.import: imported.yml\n", Map.of(), List.of(), List.of());
        assertThat(invalid.exit()).isEqualTo(78);
        assertThat(invalid.output()).contains("CONFIG.VALIDATION_FAILED", "ioc.ingestion.concurrency");
        var required = check("spring.config.import: absent.yml\n", Map.of(), List.of(), List.of());
        assertThat(required.exit()).isEqualTo(78);
        assertThat(required.output()).contains("CONFIG.CHECK_FAILED");
        var optional = check("spring.config.import: optional:file:absent.yml\n", Map.of(), List.of(), List.of());
        assertThat(optional.exit()).isZero();
    }

    @Test
    void checksTheEffectiveDeploymentRatherThanOnlyTheCandidateFile() throws Exception {
        Files.createDirectories(directory.resolve("configs"));
        Files.writeString(directory.resolve("configs/application.yml"), "ioc.ingestion.concurrency: 2\n");
        var overlay = check("ioc.runtime.mode: daemon\n", Map.of(), List.of(), List.of());
        assertThat(overlay.exit()).isEqualTo(78);
        assertThat(overlay.output()).contains("ioc.ingestion.concurrency");
        // Explicit candidate wins over the imported deployment overlay; CLI wins over env/system/JSON.
        var candidate = check("ioc.ingestion.concurrency: 1\n", Map.of(), List.of(), List.of());
        assertThat(candidate.exit()).as(candidate.output()).isZero();
        var command = check("ioc.runtime.mode: daemon\n", Map.of(
                        "IOC_INGESTION_CONCURRENCY", "2",
                        "SPRING_APPLICATION_JSON", "{\"ioc\":{\"ingestion\":{\"concurrency\":2}}}"),
                List.of("-Dioc.ingestion.concurrency=2"), List.of("--ioc.ingestion.concurrency=1"));
        assertThat(command.exit()).as(command.output()).isZero();
        assertNoRuntimeFiles();
    }

    @Test
    void selectsActiveProfileDocumentsAndProfileSpecificFiles() throws Exception {
        String documents = """
                ioc.runtime.mode: oneshot
                ---
                spring.config.activate.on-profile: qualification
                ioc.ingestion.concurrency: 2
                """;
        assertThat(check(documents, Map.of(), List.of(), List.of()).exit()).isZero();
        var active = check(documents, Map.of(), List.of(), List.of("--spring.profiles.active=qualification"));
        assertThat(active.exit()).isEqualTo(78);
        assertThat(active.output()).contains("ioc.ingestion.concurrency");
        Files.writeString(directory.resolve("candidate-qualification.yml"), "ioc.runtime.unknown-mode: profile-secret\n");
        var profileFile = check("ioc.runtime.mode: oneshot\n", Map.of(), List.of(),
                List.of("--spring.profiles.active=qualification"));
        assertThat(profileFile.exit()).isEqualTo(78);
        assertThat(profileFile.output()).contains("CONFIG.UNKNOWN_PROPERTY", "ioc.runtime.unknown-mode")
                .doesNotContain("profile-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = { "candidate", "import", "cli", "environment", "system", "json" })
    void rejectsIndexedBeanSourcesBeforeInstantiationAcrossLoadedChannels(String channel) throws Exception {
        String source = RuntimeTrap.class.getName();
        String content = "ioc.runtime.mode: oneshot\n";
        Map<String, String> environment = Map.of();
        List<String> system = List.of();
        List<String> arguments = List.of();
        switch (channel) {
            case "candidate" -> content += "spring.main.sources[0]: " + source + "\n";
            case "import" -> {
                Files.writeString(directory.resolve("extra.yml"), "spring.main.sources[0]: " + source + "\n");
                content += "spring.config.import: extra.yml\n";
            }
            case "cli" -> arguments = List.of("--spring.main.sources[0]=" + source);
            case "environment" -> environment = Map.of("SPRING_MAIN_SOURCES_0_", source);
            case "system" -> system = List.of("-Dspring.main.sources[0]=" + source);
            case "json" -> environment = Map.of("SPRING_APPLICATION_JSON",
                    "{\"spring\":{\"main\":{\"sources\":[\"" + source + "\"]}}}");
            default -> throw new AssertionError(channel);
        }
        var result = check(content, environment, system, arguments);
        assertThat(result.exit()).isEqualTo(78);
        assertThat(result.output()).contains("CONFIG.SEMANTIC_SOURCE_OVERRIDE").doesNotContain(source);
        assertThat(directory.resolve("runtime-marker")).doesNotExist();
    }

    @Test
    void runtimeLifecycleFlagsCannotKeepTheCheckerAliveOrDeferValidation() throws Exception {
        String flags = """
                spring.main.keep-alive: true
                spring.main.lazy-initialization: true
                spring.main.register-shutdown-hook: true
                spring.main.log-startup-info: true
                ioc.runtime.mode: daemon
                """;
        var valid = check(flags, Map.of(), List.of(), List.of());
        assertThat(valid.exit()).as(valid.output()).isZero();
        var invalid = check(flags + "ioc.ingestion.concurrency: 2\n", Map.of(), List.of(), List.of());
        assertThat(invalid.exit()).isEqualTo(78);
        assertThat(invalid.output()).contains("CONFIG.VALIDATION_FAILED").doesNotContain("Starting ", "Started ");
        assertNoRuntimeFiles();
    }

    @Test
    void realIocBindingReplacesTheWholeListAndRejectsIncompleteElements() throws Exception {
        Files.writeString(directory.resolve("candidate.yml"), """
                ioc.refang.rules:
                  - { from: custom, to: replacement }
                """);
        var bound = launch(Map.of(), List.of(), List.of("--qualification.model"));
        assertThat(bound.exit()).as(bound.output()).isZero();
        assertThat(bound.output()).contains("IOC_LIST_REPLACED_CONTEXT_CLOSED");
        var partial = check("ioc.refang.rules[0].from: custom\n", Map.of(), List.of(), List.of());
        assertThat(partial.exit()).isEqualTo(78);
        assertThat(partial.output()).contains("CONFIG.VALIDATION_FAILED", "ioc.refang.rules[0].to");
    }

    private Outcome check(String candidate, Map<String, String> environment,
                          List<String> system, List<String> arguments) throws Exception {
        Files.writeString(directory.resolve("candidate.yml"), candidate);
        var command = new ArrayList<>(arguments);
        command.add("--ioc.validate-config=" + directory.resolve("candidate.yml"));
        return launch(environment, system, command);
    }

    private Outcome launch(Map<String, String> environment, List<String> system, List<String> arguments)
            throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        command.addAll(system);
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, Arrays.stream(System.getProperty("java.class.path")
                .split(Pattern.quote(java.io.File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList()));
        command.add(IocConfigurationContextIT.class.getName());
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("IOC_") || name.startsWith("SPRING_")
                || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(name));
        builder.environment().putAll(environment);
        Path log = directory.resolve("process.log");
        Path result = directory.resolve("checker.exit");
        Files.deleteIfExists(result);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Configuration-check JVM terminates").isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            int code = Files.exists(result) ? Integer.parseInt(Files.readString(result)) : 0;
            return new Outcome(code, Files.readString(log));
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("Configuration-check JVM cleanup").isTrue();
        }
    }

    private void assertNoRuntimeFiles() {
        assertThat(directory.resolve("var")).doesNotExist();
        assertThat(directory.resolve("dataframe")).doesNotExist();
    }

    private record Outcome(int exit, String output) { }

    /** Child entry point deliberately avoids the full application composition root. */
    public static void main(String[] arguments) throws Exception {
        if (Arrays.asList(arguments).contains("--qualification.model")) {
            verifyBoundModelAndClose();
        } else {
            int result = IocSemanticConfigurationCheck.executeIfRequested(arguments,
                    new PrintWriter(System.out, true), new PrintWriter(System.err, true)).orElseThrow();
            Files.writeString(Path.of("checker.exit"), Integer.toString(result));
            // Returning normally on both paths exposes accidental non-daemon workers.
        }
    }

    private static void verifyBoundModelAndClose() {
        var application = new SpringApplication(IocSemanticConfigurationCheck.SemanticCheckConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        var context = application.run("--spring.config.location=classpath:/application.yml,"
                + Path.of("candidate.yml").toAbsolutePath().toUri(), "--logging.level.root=OFF");
        try (context) {
            assertThat(context.getBean(IocProperties.class).refang().rules())
                    .containsExactly(new IocProperties.Refang.Rule("custom", "replacement"));
            assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
            assertThat(context.containsBean("appConfig")).isFalse();
            assertThat(context.containsBean("cliRunner")).isFalse();
        }
        assertThat(context.isActive()).isFalse();
        System.out.println("IOC_LIST_REPLACED_CONTEXT_CLOSED");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class RuntimeTrap {
        public RuntimeTrap() throws Exception {
            Files.createFile(Path.of("runtime-marker"));
        }
    }
}
