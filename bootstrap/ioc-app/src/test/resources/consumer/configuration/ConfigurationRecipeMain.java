package examples.configuration;

import examples.configuration.CollectorConfigurationCheck.CheckFailure;
import examples.configuration.CollectorConfigurationCheck.Code;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Independent consumer assertions; deliberately has no IOC imports. */
public final class ConfigurationRecipeMain {
    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        Path candidate = directory.resolve("collector.yml");
        var initial = CollectorConfigurationCheck.check(candidate, Map.of());
        require(initial.requestTimeout().equals(Duration.ofSeconds(5)), "ambient input entered the model");
        require(initial.connections().size() == 2, "document list was not bound");
        var overridden = CollectorConfigurationCheck.check(candidate, Map.of("collector.request-timeout", "2s"));
        require(overridden.requestTimeout().equals(Duration.ofSeconds(2)), "explicit override was lost");
        require(overridden.connections().get(1).name().equals("fallback"), "scalar override changed the list");
        var replacement = CollectorConfigurationCheck.check(candidate, Map.of(
                "collector.connections[0].name", "replacement", "collector.connections[0].enabled", "true"));
        require(replacement.connections().size() == 1, "higher list source did not replace the whole list");
        require(replacement.connections().getFirst().name().equals("replacement"), "replacement was lost");
        reject(candidate, Map.of("collector.connections[0].name", "partial"), Code.BINDING_OR_VALIDATION);
        reject(candidate, Map.of("collector.request-timeout", "0s"), Code.BINDING_OR_VALIDATION);
        reject(candidate, Map.of("collector.request-timeout", "conversion-secret"), Code.BINDING_OR_VALIDATION);
        reject(candidate, Map.of("collector.unknown", "secret"), Code.NAME_CHECK);
        reject(candidate, Map.of("spring.main.sources[0]", "secret"), Code.INPUT_POLICY);
        reject(candidate, Map.of("collector.request-timeout", "${collector.ambient:1s}"), Code.INPUT_POLICY);
        String valid = Files.readString(candidate);
        // This import must be rejected as data; opening the target would instead be a YAML input failure.
        Files.writeString(directory.resolve("import.yml"), "malformed: [parser-secret\n");
        for (String forbidden : new String[] {
                "spring.config.import: import.yml", "spring.config.activate.on-profile: closed",
                "spring.profiles.active: closed", "spring.config.location: import.yml" }) {
            Files.writeString(candidate, valid + "\n" + forbidden + "\n");
            reject(candidate, Map.of(), Code.INPUT_POLICY);
        }
        Files.writeString(candidate, valid + "\ncollector.unknown: secret\n");
        reject(candidate, Map.of("collector.request-timeout", "2s"), Code.NAME_CHECK);
        Files.writeString(candidate, valid + "\n---\ncollector.request-timeout: 2s\n");
        reject(candidate, Map.of(), Code.INPUT_POLICY);
        Files.writeString(candidate, "malformed: [parser-secret\n");
        reject(candidate, Map.of(), Code.INPUT_INVALID);
        reject(directory.resolve("absent.yml"), Map.of(), Code.INPUT_INVALID);
        Files.writeString(candidate, valid);
        require(CollectorConfigurationCheck.check(candidate, Map.of()).equals(initial), "failed checks contaminated a later check");
        try {
            initial.connections().clear();
            throw new AssertionError("returned list is mutable");
        } catch (UnsupportedOperationException expected) {
            // The model is detached from the closed context.
        }
        System.out.println("RECIPE_VALIDATED");
    }

    private static void reject(Path candidate, Map<String, String> overrides, Code expected) {
        try {
            CollectorConfigurationCheck.check(candidate, overrides);
            throw new AssertionError("invalid candidate passed: " + expected);
        } catch (CheckFailure failure) {
            require(failure.code() == expected, "wrong check category: " + failure.code());
            require(failure.getCause() == null, "input-bearing exception escaped");
            require(failure.getMessage().equals(expected.name()), "input-bearing message escaped");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

/** A scan of the example's package would instantiate this component and fail the qualification. */
@Component
class CollectorRuntimeTrap {
    CollectorRuntimeTrap() throws Exception {
        Files.createFile(Path.of("runtime-marker"));
        throw new IllegalStateException("runtime-component-created");
    }
}
