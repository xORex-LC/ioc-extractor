package com.iocextractor.bootstrap;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/** Operator-facing diagnostic for YAML syntax failures during Spring ConfigData loading. */
public final class IocYamlConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<MarkedYAMLException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MarkedYAMLException cause) {
        IocYamlFailureDetails details = IocYamlFailureDetails.from(cause);
        return new FailureAnalysis(
                details.description("application YAML"),
                IocYamlFailureDetails.action(),
                cause);
    }
}
