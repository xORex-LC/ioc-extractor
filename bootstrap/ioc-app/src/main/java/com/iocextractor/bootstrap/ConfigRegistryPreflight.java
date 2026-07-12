package com.iocextractor.bootstrap;

import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup preflight for config values that reference composition-root registries.
 */
final class ConfigRegistryPreflight implements InitializingBean {

    private final IocProperties props;

    ConfigRegistryPreflight(IocProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> errors = new ArrayList<>();
        validateClassifyPredicates(errors);
        validateSinkArtifacts(errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("CONFIG.REGISTRY invalid IOC configuration:\n- "
                    + String.join("\n- ", errors));
        }
    }

    private void validateClassifyPredicates(List<String> errors) {
        if (props.classify() == null || props.classify().rules() == null) {
            return;
        }
        Set<String> allowed = ConfigRegistryCatalog.classifyPredicateKeys();
        List<IocProperties.Classify.Rule> rules = props.classify().rules();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            IocProperties.Classify.Rule rule = rules.get(ruleIndex);
            if (rule == null || rule.when() == null) {
                continue;
            }
            for (int predicateIndex = 0; predicateIndex < rule.when().size(); predicateIndex++) {
                String value = rule.when().get(predicateIndex);
                rejectUnknown(errors, allowed, value,
                        "ioc.classify.rules[%d].when[%d]".formatted(ruleIndex, predicateIndex),
                        "use a registered classify predicate");
            }
        }
    }

    private void validateSinkArtifacts(List<String> errors) {
        if (props.sink() == null || props.sink().artifacts() == null) {
            return;
        }
        Set<String> artifactFilters = ConfigRegistryCatalog.artifactFilterKeys();
        Set<String> valueProviders = ConfigRegistryCatalog.valueProviderKeys();
        Set<String> transforms = ConfigRegistryCatalog.transformKeys();
        List<IocProperties.Sink.Artifact> artifacts = props.sink().artifacts();
        for (int artifactIndex = 0; artifactIndex < artifacts.size(); artifactIndex++) {
            IocProperties.Sink.Artifact artifact = artifacts.get(artifactIndex);
            if (artifact == null) {
                continue;
            }
            validateArtifactFilters(errors, artifactFilters, artifact.include(),
                    "ioc.sink.artifacts[%d].include".formatted(artifactIndex),
                    "use a registered artifact include predicate");
            validateArtifactFilters(errors, artifactFilters, artifact.exclude(),
                    "ioc.sink.artifacts[%d].exclude".formatted(artifactIndex),
                    "use a registered artifact exclude predicate");
            validateColumns(errors, valueProviders, transforms, artifact.columns(), artifactIndex);
        }
    }

    private void validateArtifactFilters(List<String> errors,
                                         Set<String> allowed,
                                         List<String> values,
                                         String pathPrefix,
                                         String action) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            rejectUnknown(errors, allowed, values.get(i), "%s[%d]".formatted(pathPrefix, i), action);
        }
    }

    private void validateColumns(List<String> errors,
                                 Set<String> valueProviders,
                                 Set<String> transforms,
                                 List<IocProperties.Sink.Artifact.Column> columns,
                                 int artifactIndex) {
        if (columns == null) {
            return;
        }
        int idColumns = 0;
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            IocProperties.Sink.Artifact.Column column = columns.get(columnIndex);
            if (column == null) {
                continue;
            }
            validateColumnProvider(errors, valueProviders, column, artifactIndex, columnIndex);
            validateColumnTransforms(errors, transforms, column, artifactIndex, columnIndex);
            if ("id".equals(column.from())) {
                idColumns++;
                validateDeferredIdColumn(errors, column, artifactIndex, columnIndex);
            }
        }
        if (idColumns > 1) {
            errors.add("ioc.sink.artifacts[%d].columns contains %d 'id' providers; use at most one deferred id column"
                    .formatted(artifactIndex, idColumns));
        }
    }

    private void validateDeferredIdColumn(List<String> errors,
                                          IocProperties.Sink.Artifact.Column column,
                                          int artifactIndex,
                                          int columnIndex) {
        String path = "ioc.sink.artifacts[%d].columns[%d]".formatted(artifactIndex, columnIndex);
        if (column.whenType() != null) {
            errors.add(path + ".when-type is not supported for the deferred 'id' provider; remove the gate");
        }
        if (column.transform() != null && !column.transform().isEmpty()) {
            errors.add(path + ".transform is not supported for the deferred 'id' provider; remove transforms");
        }
    }

    private void validateColumnProvider(List<String> errors,
                                        Set<String> valueProviders,
                                        IocProperties.Sink.Artifact.Column column,
                                        int artifactIndex,
                                        int columnIndex) {
        String provider = column.from();
        if (ConfigRegistryCatalog.CONST_VALUE_PROVIDER.equals(provider)) {
            return;
        }
        rejectUnknown(errors, valueProviders, provider,
                "ioc.sink.artifacts[%d].columns[%d].from".formatted(artifactIndex, columnIndex),
                "use a registered value provider or 'const'");
    }

    private void validateColumnTransforms(List<String> errors,
                                          Set<String> transforms,
                                          IocProperties.Sink.Artifact.Column column,
                                          int artifactIndex,
                                          int columnIndex) {
        if (column.transform() == null) {
            return;
        }
        for (int transformIndex = 0; transformIndex < column.transform().size(); transformIndex++) {
            String spec = column.transform().get(transformIndex);
            String name = transformName(spec);
            rejectUnknown(errors, transforms, name,
                    "ioc.sink.artifacts[%d].columns[%d].transform[%d]"
                            .formatted(artifactIndex, columnIndex, transformIndex),
                    "use a registered transform name; arguments are written as name:arg");
        }
    }

    private void rejectUnknown(List<String> errors,
                               Set<String> allowed,
                               String value,
                               String path,
                               String action) {
        if (value != null && !value.isBlank() && allowed.contains(value)) {
            return;
        }
        errors.add("%s='%s' is unknown; allowed values: %s; %s"
                .formatted(path, printable(value), allowedValues(allowed), action));
    }

    private static String transformName(String spec) {
        if (spec == null) {
            return null;
        }
        int separator = spec.indexOf(':');
        return separator < 0 ? spec : spec.substring(0, separator);
    }

    private static String printable(String value) {
        return value == null ? "<null>" : value;
    }

    private static String allowedValues(Set<String> values) {
        return values.stream()
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
