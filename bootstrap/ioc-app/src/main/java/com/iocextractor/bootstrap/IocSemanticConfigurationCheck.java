package com.iocextractor.bootstrap;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * Side-effect-free strict binding and semantic preflight for one IOC YAML file.
 *
 * <p>The check starts a deliberately minimal Spring context containing only the
 * configuration-properties model and its preflight infrastructure. Runtime
 * composition, storage, schedulers and transport adapters are not scanned.</p>
 */
public final class IocSemanticConfigurationCheck {

    /** Standard {@code sysexits.h} code for invalid configuration. */
    public static final int CONFIGURATION_ERROR_EXIT_CODE = 78;

    private static final String ARGUMENT_PREFIX = "--ioc.validate-config=";
    private static final String LOGGING_APPLICATION_LISTENER = className(
            "org.springframework.boot.context.logging.", "LoggingApplicationListener");
    private static final Object VALIDATION_MONITOR = new Object();
    private static final List<String> FORBIDDEN_CONFIG_ARGUMENTS = List.of(
            "--spring.config.location=",
            "--spring.config.additional-location=",
            "--spring.config.import=",
            "--spring.config.name=");

    private IocSemanticConfigurationCheck() {
    }

    /**
     * Executes the semantic-check path when its internal argument is present.
     *
     * @return an exit code when handled; otherwise empty
     */
    public static OptionalInt executeIfRequested(String[] args, PrintWriter out, PrintWriter err) {
        List<String> requested = Arrays.stream(args)
                .filter(argument -> argument.startsWith(ARGUMENT_PREFIX))
                .toList();
        if (requested.isEmpty()) {
            return OptionalInt.empty();
        }
        if (requested.size() != 1) {
            return usageError(err, "Semantic validation accepts exactly one configuration file.");
        }

        String configuredPath = requested.getFirst().substring(ARGUMENT_PREFIX.length());
        if (configuredPath.isBlank()) {
            return usageError(err, "A non-empty YAML file path is required.");
        }
        List<String> effectiveArguments = Arrays.stream(args)
                .filter(argument -> !argument.startsWith(ARGUMENT_PREFIX))
                .toList();
        if (effectiveArguments.stream().anyMatch(IocSemanticConfigurationCheck::controlsConfigLoading)) {
            return usageError(err,
                    "spring.config location, import and name arguments are owned by semantic validation.");
        }

        try {
            return OptionalInt.of(validate(
                    Path.of(configuredPath), effectiveArguments, out, err));
        } catch (InvalidPathException failure) {
            err.println("CONFIG.SEMANTIC_UNREADABLE");
            err.println("Configuration path is invalid.");
            return OptionalInt.of(CONFIGURATION_ERROR_EXIT_CODE);
        }
    }

    static int validate(
            Path configuredPath,
            List<String> effectiveArguments,
            PrintWriter out,
            PrintWriter err) {
        synchronized (VALIDATION_MONITOR) {
            return validateQuietly(configuredPath, effectiveArguments, out, err);
        }
    }

    private static int validateQuietly(
            Path configuredPath,
            List<String> effectiveArguments,
            PrintWriter out,
            PrintWriter err) {
        ConsoleOutputScope outputScope = ConsoleOutputScope.suppress();
        try {
            return validateWithSpring(configuredPath, effectiveArguments, out, err);
        } finally {
            outputScope.restore();
        }
    }

    private static int validateWithSpring(
            Path configuredPath,
            List<String> effectiveArguments,
            PrintWriter out,
            PrintWriter err) {
        Path path = configuredPath.toAbsolutePath().normalize();
        int syntaxResult = IocYamlSyntaxCheck.validate(
                path, new PrintWriter(Writer.nullWriter()), err);
        if (syntaxResult != 0) {
            return syntaxResult;
        }

        SpringApplication application = new SpringApplication(SemanticCheckConfiguration.class);
        application.setListeners(application.getListeners().stream()
                .filter(listener -> !listener.getClass().getName().equals(LOGGING_APPLICATION_LISTENER))
                .toList());
        application.addListeners(new SemanticSourceGuard());
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setWebApplicationType(WebApplicationType.NONE);
        List<String> springArguments = new ArrayList<>(effectiveArguments);
        springArguments.add("--spring.config.location=classpath:/application.yml," + path.toUri());
        springArguments.add("--spring.main.banner-mode=off");
        springArguments.add("--spring.main.web-application-type=none");
        springArguments.add("--spring.jmx.enabled=false");
        springArguments.add("--debug=false");
        springArguments.add("--trace=false");
        springArguments.add("--logging.level.root=OFF");

        try (ConfigurableApplicationContext ignored = application.run(springArguments.toArray(String[]::new))) {
            out.println("CONFIG.SEMANTIC_VALID: " + path);
            return 0;
        } catch (RuntimeException failure) {
            reportFailure(path, failure, err);
            return CONFIGURATION_ERROR_EXIT_CODE;
        }
    }

    private static boolean controlsConfigLoading(String argument) {
        return FORBIDDEN_CONFIG_ARGUMENTS.stream().anyMatch(argument::startsWith);
    }

    private static String className(String packageName, String simpleName) {
        return packageName.concat(simpleName);
    }

    private static OptionalInt usageError(PrintWriter err, String message) {
        err.println("CONFIG.SEMANTIC_USAGE");
        err.println(message);
        return OptionalInt.of(CONFIGURATION_ERROR_EXIT_CODE);
    }

    private static void reportFailure(Path path, RuntimeException failure, PrintWriter err) {
        err.println("CONFIG.SEMANTIC_INVALID: " + path);
        if (findCause(failure, SemanticSourceOverrideException.class) != null) {
            err.println("CONFIG.SEMANTIC_SOURCE_OVERRIDE");
            err.println("spring.main.sources is not supported by configuration validation.");
            return;
        }
        FailureAnalysis analysis = new IocConfigurationFailureAnalyzer().analyze(failure);
        if (analysis != null) {
            err.println(analysis.getDescription());
            err.println(analysis.getAction());
            return;
        }
        Throwable informative = informativeCause(failure);
        err.println(informative.getClass().getSimpleName());
        if (informative.getMessage() != null && !informative.getMessage().isBlank()) {
            err.println(informative.getMessage());
        }
    }

    private static Throwable informativeCause(Throwable failure) {
        Throwable current = failure;
        Throwable informative = failure;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                informative = current;
            }
            current = current.getCause();
        }
        return informative;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class SemanticSourceGuard
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            if (event.getEnvironment().containsProperty("spring.main.sources")) {
                throw new SemanticSourceOverrideException();
            }
        }
    }

    private static final class SemanticSourceOverrideException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /** Prevents effective logging configuration from exposing values during validation. */
    private static final class ConsoleOutputScope {

        private final PrintStream standardOutput;
        private final PrintStream standardError;
        private final PrintStream sink;

        private ConsoleOutputScope(PrintStream standardOutput, PrintStream standardError, PrintStream sink) {
            this.standardOutput = standardOutput;
            this.standardError = standardError;
            this.sink = sink;
        }

        private static ConsoleOutputScope suppress() {
            PrintStream sink = new PrintStream(
                    OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
            ConsoleOutputScope scope = new ConsoleOutputScope(System.out, System.err, sink);
            System.setOut(sink);
            System.setErr(sink);
            return scope;
        }

        private void restore() {
            System.setOut(standardOutput);
            System.setErr(standardError);
            sink.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IocProperties.class)
    @Import(ConfigPreflightConfiguration.class)
    static class SemanticCheckConfiguration {
    }
}
