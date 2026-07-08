package com.iocextractor.bootstrap;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        validateLegacyLookup(props, errors);
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

    private void validateLegacyLookup(IocProperties props, Errors errors) {
        IocProperties.Lookup lookup = props.lookup();
        if (lookup == null) {
            return;
        }
        if (lookup.deduplicate() != null) {
            reject(errors, "lookup.deduplicate", lookup.deduplicate(),
                    "ioc.lookup.deduplicate was moved; use ioc.pipeline.deduplicate");
        }
        if (lookup.type() != null) {
            rejectRemovedLookup(errors, "lookup.type", lookup.type());
        }
        if (lookup.path() != null) {
            rejectRemovedLookup(errors, "lookup.path", lookup.path());
        }
        if (lookup.artifacts() != null) {
            rejectRemovedLookup(errors, "lookup.artifacts", lookup.artifacts());
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

    @SuppressWarnings("deprecation")
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
        if (smb.readTimeout() != null) {
            reject(errors, prefix + "readTimeout", smb.readTimeout(),
                    "ioc.sync.endpoints[%d].smb.read-timeout was removed; use request-timeout"
                            .formatted(endpointIndex));
        }
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

    private void rejectRemovedLookup(Errors errors, String field, Object value) {
        reject(errors, field, value,
                "legacy ioc.lookup.* storage keys are removed; SQLite/JDBC dataframe storage is the runtime truth");
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
}
