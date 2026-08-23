package com.iocextractor.bootstrap;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * Side-effect-free YAML syntax check executed before a Spring context is created.
 *
 * <p>This is an internal packaging entry point. It intentionally validates syntax only;
 * typed binding and semantic validation remain owned by normal application startup.</p>
 */
public final class IocYamlSyntaxCheck {

    /** Standard {@code sysexits.h} code for invalid configuration. */
    public static final int CONFIGURATION_ERROR_EXIT_CODE = 78;

    private static final String ARGUMENT_PREFIX = "--ioc.validate-yaml=";

    private IocYamlSyntaxCheck() {
    }

    /**
     * Executes the syntax-only path when the internal validation argument is present.
     *
     * @return an exit code when handled; otherwise empty
     */
    public static OptionalInt executeIfRequested(String[] args, PrintWriter out, PrintWriter err) {
        boolean requested = java.util.Arrays.stream(args).anyMatch(argument -> argument.startsWith(ARGUMENT_PREFIX));
        if (!requested) {
            return OptionalInt.empty();
        }
        if (args.length != 1) {
            err.println("CONFIG.YAML_USAGE");
            err.println("YAML validation accepts exactly one --ioc.validate-yaml=<file> argument.");
            return OptionalInt.of(CONFIGURATION_ERROR_EXIT_CODE);
        }
        String configuredPath = args[0].substring(ARGUMENT_PREFIX.length());
        if (configuredPath.isBlank()) {
            err.println("CONFIG.YAML_UNREADABLE");
            err.println("A non-empty YAML file path is required.");
            return OptionalInt.of(CONFIGURATION_ERROR_EXIT_CODE);
        }
        try {
            return OptionalInt.of(validate(Path.of(configuredPath), out, err));
        } catch (InvalidPathException failure) {
            err.println("CONFIG.YAML_UNREADABLE");
            err.println("YAML configuration path is invalid.");
            return OptionalInt.of(CONFIGURATION_ERROR_EXIT_CODE);
        }
    }

    static int validate(Path configuredPath, PrintWriter out, PrintWriter err) {
        Path path = configuredPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            err.println("CONFIG.YAML_UNREADABLE");
            err.println("YAML configuration is not a readable regular file: " + path);
            return CONFIGURATION_ERROR_EXIT_CODE;
        }

        try {
            new YamlPropertySourceLoader().load("syntax-check", new FileSystemResource(path));
            out.println("CONFIG.YAML_VALID: " + path);
            return 0;
        } catch (IOException failure) {
            err.println("CONFIG.YAML_UNREADABLE");
            err.println("Could not read YAML configuration: " + path);
            return CONFIGURATION_ERROR_EXIT_CODE;
        } catch (RuntimeException failure) {
            MarkedYAMLException yamlFailure = findYamlFailure(failure);
            if (yamlFailure == null) {
                err.println("CONFIG.YAML_INVALID");
                err.println("YAML configuration could not be loaded: " + path);
            } else {
                IocYamlFailureDetails details = IocYamlFailureDetails.from(yamlFailure);
                err.println(details.description(path.toString()));
                err.println(IocYamlFailureDetails.action());
            }
            return CONFIGURATION_ERROR_EXIT_CODE;
        }
    }

    private static MarkedYAMLException findYamlFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MarkedYAMLException yamlFailure) {
                return yamlFailure;
            }
            current = current.getCause();
        }
        return null;
    }
}
