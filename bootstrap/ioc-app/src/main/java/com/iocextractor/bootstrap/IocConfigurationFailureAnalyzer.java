package com.iocextractor.bootstrap;

import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Operator-facing diagnostics for removed or unknown {@code ioc.*} keys.
 */
public final class IocConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<UnboundConfigurationPropertiesException> {

    private static final Pattern SMB_READ_TIMEOUT = Pattern.compile(
            "ioc\\.sync\\.endpoints\\[\\d+]\\.smb\\.read-timeout");

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, UnboundConfigurationPropertiesException cause) {
        Set<UnknownKey> keys = unknownIocKeys(cause);
        if (keys.isEmpty()) {
            return null;
        }
        return new FailureAnalysis(description(keys), action(keys), cause);
    }

    private Set<UnknownKey> unknownIocKeys(UnboundConfigurationPropertiesException cause) {
        Set<UnknownKey> keys = new LinkedHashSet<>();
        cause.getUnboundProperties().stream()
                .map(property -> new UnknownKey(
                        property.getName().toString(),
                        rawEnvironmentName(property.getValue())))
                .filter(key -> key.canonicalName().startsWith("ioc."))
                .sorted(java.util.Comparator.comparing(UnknownKey::canonicalName))
                .forEach(keys::add);
        return keys;
    }

    private String description(Set<UnknownKey> keys) {
        return """
                CONFIG.UNKNOWN_PROPERTY
                Unknown or removed IOC configuration keys were found:
                %s""".formatted(bullets(keys));
    }

    private String action(Set<UnknownKey> keys) {
        Set<String> actions = new LinkedHashSet<>();
        for (UnknownKey key : keys) {
            String canonical = key.canonicalName();
            if ("ioc.lookup.deduplicate".equals(canonical)) {
                actions.add("CONFIG.LEGACY_LOOKUP: replace ioc.lookup.deduplicate with ioc.pipeline.deduplicate.");
            } else if (canonical.startsWith("ioc.lookup.")) {
                actions.add("CONFIG.LEGACY_LOOKUP: remove legacy ioc.lookup.* keys; CSV lookup storage was retired.");
            } else if (SMB_READ_TIMEOUT.matcher(canonical).matches()) {
                actions.add(
                        "CONFIG.LEGACY_SYNC_TIMEOUT: replace ioc.sync.endpoints[].smb.read-timeout with request-timeout.");
            }
        }
        actions.add("Remove or rename every unknown ioc.* key, then restart the application.");
        return String.join(System.lineSeparator(), actions);
    }

    private String bullets(Set<UnknownKey> keys) {
        StringBuilder result = new StringBuilder();
        for (UnknownKey key : keys) {
            if (!result.isEmpty()) {
                result.append(System.lineSeparator());
            }
            result.append("- ").append(key.displayName());
        }
        return result.toString();
    }

    private String rawEnvironmentName(Object value) {
        return value instanceof String string && string.startsWith("IOC_") ? string : null;
    }

    private record UnknownKey(String canonicalName, String rawEnvironmentName) {

        String displayName() {
            return rawEnvironmentName == null ? canonicalName : rawEnvironmentName + " (" + canonicalName + ")";
        }
    }
}
