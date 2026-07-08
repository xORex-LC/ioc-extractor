package com.iocextractor.bootstrap;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic startup preflight for the bound {@code ioc.*} configuration tree.
 */
final class IocConfigPreflight implements Validator {

    private static final String ERROR_CODE = "ioc.config.invalid";

    @Override
    public boolean supports(Class<?> clazz) {
        return IocProperties.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        if (!(target instanceof IocProperties props)) {
            return;
        }
        validateDataframeStorage(props, errors);
        validateArtifactIdentityReferences(props, errors);
        validateSync(props, errors);
    }

    private void validateDataframeStorage(IocProperties props, Errors errors) {
        IocProperties.Storage storage = props.storage();
        if (storage == null || storage.dataframe() == null) {
            return;
        }
        String type = storage.dataframe().type();
        if (hasText(type) && !"jdbc".equalsIgnoreCase(type)) {
            reject(errors, "storage.dataframe.type", type,
                    "ioc.storage.dataframe.type='%s' is not supported; set it to 'jdbc'".formatted(type));
        }
    }

    private void validateSync(IocProperties props, Errors errors) {
        IocProperties.Sync sync = props.sync();
        if (sync == null) {
            return;
        }
        validateRetry(sync.retry(), errors);
        Set<String> endpointNames = validateEndpoints(sync.endpoints(), errors);
        validateFetch(sync.fetch(), endpointNames, errors);
        validatePublish(sync.publish(), endpointNames, exportProfiles(props.export()), errors);
    }

    private void validateArtifactIdentityReferences(IocProperties props, Errors errors) {
        Map<String, SinkArtifactRef> sinkArtifacts = validateSinkArtifacts(props.sink(), errors);
        Set<String> identityArtifacts = validateIdentityDefinitions(props.artifactIdentity(), sinkArtifacts, errors);
        for (SinkArtifactRef sinkArtifact : sinkArtifacts.values()) {
            IocProperties.Sink.Artifact artifact = sinkArtifact.artifact();
            if (artifact.enabled() && hasText(artifact.name()) && !identityArtifacts.contains(artifact.name())) {
                reject(errors, "sink.artifacts[%d].name".formatted(sinkArtifact.index()), artifact.name(),
                        "ioc.sink.artifacts[%d].name='%s' is enabled but has no identity definition; add matching ioc.artifact-identity.artifacts[].name"
                                .formatted(sinkArtifact.index(), artifact.name()));
            }
        }
    }

    private Map<String, SinkArtifactRef> validateSinkArtifacts(IocProperties.Sink sink, Errors errors) {
        Map<String, SinkArtifactRef> result = new LinkedHashMap<>();
        if (sink == null || sink.artifacts() == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        List<IocProperties.Sink.Artifact> artifacts = sink.artifacts();
        for (int i = 0; i < artifacts.size(); i++) {
            IocProperties.Sink.Artifact artifact = artifacts.get(i);
            if (artifact == null) {
                reject(errors, "sink.artifacts[%d]".formatted(i), null,
                        "ioc.sink.artifacts[%d] is invalid; configure an artifact object".formatted(i));
                continue;
            }
            Set<String> columnNames = validateSinkArtifactColumns(artifact, i, errors);
            validateArtifactIdStart(artifact, i, columnNames, errors);
            if (!hasText(artifact.name())) {
                continue;
            }
            if (seen.add(artifact.name())) {
                result.put(artifact.name(), new SinkArtifactRef(i, artifact, columnNames));
            } else {
                reject(errors, "sink.artifacts[%d].name".formatted(i), artifact.name(),
                        "ioc.sink.artifacts[%d].name='%s' duplicates another sink artifact; use unique names"
                                .formatted(i, artifact.name()));
            }
        }
        return result;
    }

    private Set<String> validateSinkArtifactColumns(IocProperties.Sink.Artifact artifact,
                                                    int artifactIndex,
                                                    Errors errors) {
        Set<String> columnNames = new HashSet<>();
        if (artifact.columns() == null) {
            return columnNames;
        }
        Set<String> seen = new HashSet<>();
        List<IocProperties.Sink.Artifact.Column> columns = artifact.columns();
        for (int i = 0; i < columns.size(); i++) {
            IocProperties.Sink.Artifact.Column column = columns.get(i);
            if (column == null) {
                reject(errors, "sink.artifacts[%d].columns[%d]".formatted(artifactIndex, i), null,
                        "ioc.sink.artifacts[%d].columns[%d] is invalid; configure a column object"
                                .formatted(artifactIndex, i));
                continue;
            }
            String name = column.name();
            if (!hasText(name)) {
                continue;
            }
            columnNames.add(name);
            rejectDuplicate(errors, seen, name,
                    "sink.artifacts[%d].columns[%d].name".formatted(artifactIndex, i),
                    "ioc.sink.artifacts[%d].columns[%d].name".formatted(artifactIndex, i),
                    "sink artifact column");
        }
        return columnNames;
    }

    private void validateArtifactIdStart(IocProperties.Sink.Artifact artifact,
                                         int artifactIndex,
                                         Set<String> columnNames,
                                         Errors errors) {
        IocProperties.Sink.Artifact.Id id = artifact.id();
        if (id == null || !hasText(id.start()) || !isExplicitNumeric(id.start()) || columnNames.contains("id")) {
            return;
        }
        reject(errors, "sink.artifacts[%d].id.start".formatted(artifactIndex), id.start(),
                "ioc.sink.artifacts[%d].id.start='%s' is numeric but artifact '%s' has no public id column; remove id.start or add an id column"
                        .formatted(artifactIndex, id.start(), artifact.name()));
    }

    private Set<String> validateIdentityDefinitions(IocProperties.ArtifactIdentity identity,
                                                    Map<String, SinkArtifactRef> sinkArtifacts,
                                                    Errors errors) {
        Set<String> identityNames = new HashSet<>();
        if (identity == null || identity.artifacts() == null) {
            return identityNames;
        }
        Set<String> seen = new HashSet<>();
        List<IocProperties.ArtifactIdentity.Artifact> artifacts = identity.artifacts();
        for (int i = 0; i < artifacts.size(); i++) {
            IocProperties.ArtifactIdentity.Artifact artifact = artifacts.get(i);
            if (artifact == null) {
                reject(errors, "artifactIdentity.artifacts[%d]".formatted(i), null,
                        "ioc.artifact-identity.artifacts[%d] is invalid; configure an identity object".formatted(i));
                continue;
            }
            String name = artifact.name();
            SinkArtifactRef sinkArtifact = null;
            if (hasText(name)) {
                identityNames.add(name);
                rejectDuplicate(errors, seen, name, "artifactIdentity.artifacts[%d].name".formatted(i),
                        "ioc.artifact-identity.artifacts[%d].name".formatted(i), "artifact identity");
                sinkArtifact = sinkArtifacts.get(name);
                if (sinkArtifact == null) {
                    reject(errors, "artifactIdentity.artifacts[%d].name".formatted(i), name,
                            "ioc.artifact-identity.artifacts[%d].name='%s' is unknown; reference an ioc.sink.artifacts[].name"
                                    .formatted(i, name));
                }
            }
            validateIdentityKeyColumns(artifact, i, sinkArtifact, errors);
        }
        return identityNames;
    }

    private void validateIdentityKeyColumns(IocProperties.ArtifactIdentity.Artifact artifact,
                                            int identityIndex,
                                            SinkArtifactRef sinkArtifact,
                                            Errors errors) {
        List<String> keyColumns = artifact.keyColumns();
        if (keyColumns == null) {
            return;
        }
        for (int i = 0; i < keyColumns.size(); i++) {
            String keyColumn = keyColumns.get(i);
            String field = "artifactIdentity.artifacts[%d].keyColumns[%d]".formatted(identityIndex, i);
            String configKey = "ioc.artifact-identity.artifacts[%d].key-columns[%d]".formatted(identityIndex, i);
            if (!hasText(keyColumn)) {
                reject(errors, field, keyColumn, "%s is blank; reference a configured sink column".formatted(configKey));
                continue;
            }
            if (sinkArtifact != null && !sinkArtifact.columnNames().contains(keyColumn)) {
                reject(errors, field, keyColumn,
                        "%s='%s' is unknown for artifact '%s'; reference one of its ioc.sink.artifacts[].columns[].name values"
                                .formatted(configKey, keyColumn, artifact.name()));
            }
        }
    }

    private void validateRetry(IocProperties.Sync.Retry retry, Errors errors) {
        if (retry == null) {
            return;
        }
        if (retry.maxAttempts() < 1) {
            reject(errors, "sync.retry.maxAttempts", retry.maxAttempts(),
                    "ioc.sync.retry.max-attempts=%d is invalid; set it to at least 1"
                            .formatted(retry.maxAttempts()));
        }
        rejectIfNotPositive(errors, "sync.retry.backoff", retry.backoff(), "ioc.sync.retry.backoff");
        rejectIfNotPositive(errors, "sync.retry.maxBackoff", retry.maxBackoff(), "ioc.sync.retry.max-backoff");
        if (retry.multiplier() < 1.0d) {
            reject(errors, "sync.retry.multiplier", retry.multiplier(),
                    "ioc.sync.retry.multiplier=%s is invalid; set it to at least 1.0"
                            .formatted(retry.multiplier()));
        }
        if (isPositive(retry.backoff()) && isPositive(retry.maxBackoff())
                && retry.maxBackoff().compareTo(retry.backoff()) < 0) {
            reject(errors, "sync.retry.maxBackoff", retry.maxBackoff(),
                    "ioc.sync.retry.max-backoff=%s is invalid; set it greater than or equal to ioc.sync.retry.backoff"
                            .formatted(retry.maxBackoff()));
        }
    }

    private Set<String> validateEndpoints(List<IocProperties.Sync.Endpoint> endpoints, Errors errors) {
        Set<String> endpointNames = new HashSet<>();
        if (endpoints == null) {
            return endpointNames;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < endpoints.size(); i++) {
            IocProperties.Sync.Endpoint endpoint = endpoints.get(i);
            if (endpoint == null) {
                reject(errors, "sync.endpoints[%d]".formatted(i), null,
                        "ioc.sync.endpoints[%d] is invalid; configure an endpoint object".formatted(i));
                continue;
            }
            String name = endpoint.name();
            if (hasText(name)) {
                endpointNames.add(name);
                rejectDuplicate(errors, seen, name, "sync.endpoints[%d].name".formatted(i),
                        "ioc.sync.endpoints[%d].name".formatted(i), "sync endpoint");
            }
            if ("smb".equals(normalize(endpoint.transport())) && endpoint.smb() == null) {
                reject(errors, "sync.endpoints[%d].smb".formatted(i), null,
                        "ioc.sync.endpoints[%d].smb is required when transport is 'smb'".formatted(i));
            }
            validateSmb(endpoint.smb(), i, errors);
        }
        return endpointNames;
    }

    private void validateSmb(IocProperties.Sync.Endpoint.Smb smb, int endpointIndex, Errors errors) {
        if (smb == null) {
            return;
        }
        String prefix = "sync.endpoints[%d].smb.".formatted(endpointIndex);
        rejectIfNotPositive(errors, prefix + "connectTimeout", smb.connectTimeout(),
                "ioc.sync.endpoints[%d].smb.connect-timeout".formatted(endpointIndex));
        rejectIfNotPositive(errors, prefix + "requestTimeout", smb.requestTimeout(),
                "ioc.sync.endpoints[%d].smb.request-timeout".formatted(endpointIndex));
        rejectIfNotPositive(errors, prefix + "idleTimeout", smb.idleTimeout(),
                "ioc.sync.endpoints[%d].smb.idle-timeout".formatted(endpointIndex));
    }

    private void validateFetch(IocProperties.Sync.Fetch fetch, Set<String> endpointNames, Errors errors) {
        if (fetch == null) {
            return;
        }
        rejectIfNotPositive(errors, "sync.fetch.interval", fetch.interval(), "ioc.sync.fetch.interval");
        List<IocProperties.Sync.Fetch.Source> sources = fetch.sources();
        if (sources == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            IocProperties.Sync.Fetch.Source source = sources.get(i);
            if (source == null) {
                reject(errors, "sync.fetch.sources[%d]".formatted(i), null,
                        "ioc.sync.fetch.sources[%d] is invalid; configure a fetch source object".formatted(i));
                continue;
            }
            rejectDuplicate(errors, seen, source.name(), "sync.fetch.sources[%d].name".formatted(i),
                    "ioc.sync.fetch.sources[%d].name".formatted(i), "sync fetch source");
            validateEndpointReference(errors, endpointNames, source.endpoint(),
                    "sync.fetch.sources[%d].endpoint".formatted(i),
                    "ioc.sync.fetch.sources[%d].endpoint".formatted(i));
            validateChangeNotify(source.changeNotify(), i, errors);
        }
    }

    private void validateChangeNotify(IocProperties.Sync.Fetch.Source.ChangeNotify changeNotify,
                                      int sourceIndex,
                                      Errors errors) {
        if (changeNotify == null) {
            return;
        }
        rejectIfNotPositive(errors, "sync.fetch.sources[%d].changeNotify.debounce".formatted(sourceIndex),
                changeNotify.debounce(), "ioc.sync.fetch.sources[%d].change-notify.debounce".formatted(sourceIndex));
    }

    private void validatePublish(IocProperties.Sync.Publish publish,
                                 Set<String> endpointNames,
                                 Set<String> exportProfiles,
                                 Errors errors) {
        if (publish == null) {
            return;
        }
        rejectIfNotPositive(errors, "sync.publish.interval", publish.interval(), "ioc.sync.publish.interval");
        List<IocProperties.Sync.Publish.Target> targets = publish.targets();
        if (targets == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            IocProperties.Sync.Publish.Target target = targets.get(i);
            if (target == null) {
                reject(errors, "sync.publish.targets[%d]".formatted(i), null,
                        "ioc.sync.publish.targets[%d] is invalid; configure a publish target object".formatted(i));
                continue;
            }
            rejectDuplicate(errors, seen, target.name(), "sync.publish.targets[%d].name".formatted(i),
                    "ioc.sync.publish.targets[%d].name".formatted(i), "sync publish target");
            validateEndpointReference(errors, endpointNames, target.endpoint(),
                    "sync.publish.targets[%d].endpoint".formatted(i),
                    "ioc.sync.publish.targets[%d].endpoint".formatted(i));
            if (hasText(target.exportProfile()) && !exportProfiles.contains(target.exportProfile())) {
                reject(errors, "sync.publish.targets[%d].exportProfile".formatted(i), target.exportProfile(),
                        "ioc.sync.publish.targets[%d].export-profile='%s' is unknown; reference an ioc.export.profiles[].name"
                                .formatted(i, target.exportProfile()));
            }
        }
    }

    private Set<String> exportProfiles(IocProperties.Export export) {
        Set<String> result = new HashSet<>();
        if (export == null || export.profiles() == null) {
            return result;
        }
        for (IocProperties.Export.Profile profile : export.profiles()) {
            if (profile != null && hasText(profile.name())) {
                result.add(profile.name());
            }
        }
        return result;
    }

    private void validateEndpointReference(Errors errors,
                                           Set<String> endpointNames,
                                           String endpoint,
                                           String field,
                                           String configKey) {
        if (hasText(endpoint) && !endpointNames.contains(endpoint)) {
            reject(errors, field, endpoint,
                    "%s='%s' is unknown; reference an ioc.sync.endpoints[].name"
                            .formatted(configKey, endpoint));
        }
    }

    private void rejectDuplicate(Errors errors,
                                 Set<String> seen,
                                 String value,
                                 String field,
                                 String configKey,
                                 String label) {
        if (!hasText(value)) {
            return;
        }
        if (!seen.add(value)) {
            reject(errors, field, value,
                    "%s='%s' duplicates another %s; use unique names".formatted(configKey, value, label));
        }
    }

    private void rejectIfNotPositive(Errors errors, String field, Duration value, String configKey) {
        if (value != null && !isPositive(value)) {
            reject(errors, field, value, "%s=%s is invalid; set a positive duration".formatted(configKey, value));
        }
    }

    private void reject(Errors errors, String field, Object value, String message) {
        errors.rejectValue(field, ERROR_CODE, new Object[] { value }, message);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isExplicitNumeric(String value) {
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private record SinkArtifactRef(
            int index,
            IocProperties.Sink.Artifact artifact,
            Set<String> columnNames) {
    }
}
