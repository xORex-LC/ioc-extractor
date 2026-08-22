package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class IocYamlConfigurationFailureAnalyzerTest {

    @Test
    void convertsConfigDataYamlFailureIntoValueFreeOperatorDiagnostic() {
        String sensitiveLine = "literal-password-that-must-not-be-logged";
        Throwable loadingFailure = catchThrowable(() -> new YamlPropertySourceLoader().load(
                "broken",
                new ByteArrayResource((sensitiveLine + System.lineSeparator() + "spring:")
                        .getBytes(StandardCharsets.UTF_8))));
        MarkedYAMLException yamlFailure = findYamlFailure(loadingFailure);

        FailureAnalysis analysis = new IocYamlConfigurationFailureAnalyzer().analyze(yamlFailure);

        assertThat(analysis.getDescription())
                .contains("CONFIG.YAML_INVALID")
                .contains("Location: application YAML:2:7")
                .doesNotContain(sensitiveLine);
        assertThat(analysis.getAction())
                .contains("ioc-config apply")
                .doesNotContain("systemctl restart");
    }

    private static MarkedYAMLException findYamlFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MarkedYAMLException yamlFailure) {
                return yamlFailure;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected a MarkedYAMLException", failure);
    }
}
