package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IocConfigurationFailureAnalyzerTest {

    @Test
    void reportsLegacyLookupMigrationHints() {
        FailureAnalysis analysis = analyze(
                "ioc.lookup.deduplicate",
                "ioc.lookup.path");

        assertThat(analysis.getDescription())
                .contains("CONFIG.UNKNOWN_PROPERTY")
                .contains("ioc.lookup.deduplicate")
                .contains("ioc.lookup.path");
        assertThat(analysis.getAction())
                .contains("CONFIG.LEGACY_LOOKUP")
                .contains("replace ioc.lookup.deduplicate with ioc.pipeline.deduplicate")
                .contains("remove legacy ioc.lookup.* keys");
    }

    @Test
    void reportsLegacySmbReadTimeoutMigrationHint() {
        FailureAnalysis analysis = analyze("ioc.sync.endpoints[0].smb.read-timeout");

        assertThat(analysis.getDescription())
                .contains("CONFIG.UNKNOWN_PROPERTY")
                .contains("ioc.sync.endpoints[0].smb.read-timeout");
        assertThat(analysis.getAction())
                .contains("CONFIG.LEGACY_SYNC_TIMEOUT")
                .contains("replace ioc.sync.endpoints[].smb.read-timeout with request-timeout");
    }

    @Test
    void reportsLegacySmbEncryptionMigrationHint() {
        FailureAnalysis analysis = analyze("ioc.sync.endpoints[0].smb.encrypt");

        assertThat(analysis.getDescription())
                .contains("CONFIG.UNKNOWN_PROPERTY")
                .contains("ioc.sync.endpoints[0].smb.encrypt");
        assertThat(analysis.getAction())
                .contains("CONFIG.LEGACY_SMB_ENCRYPTION")
                .contains("replace ioc.sync.endpoints[].smb.encrypt")
                .contains("required, preferred or disabled");
    }

    @Test
    void showsRawEnvironmentNameWithoutChangingLegacyClassification() {
        ConfigurationProperty property = new ConfigurationProperty(
                ConfigurationPropertyName.of("ioc.lookup.deduplicate"), "IOC_LOOKUP_DEDUPLICATE", null);
        FailureAnalysis analysis = new IocConfigurationFailureAnalyzer()
                .analyze(new UnboundConfigurationPropertiesException(Set.of(property)));

        assertThat(analysis.getDescription()).contains("IOC_LOOKUP_DEDUPLICATE (ioc.lookup.deduplicate)");
        assertThat(analysis.getAction()).contains("CONFIG.LEGACY_LOOKUP");
    }

    @Test
    void recognizesLegacySmbTimeoutFromRawEnvironmentName() {
        ConfigurationProperty property = new ConfigurationProperty(
                ConfigurationPropertyName.of("ioc.sync.endpoints[0].smb.read.timeout"),
                "ioc_sync_endpoints_0_smb_read_timeout", null);
        FailureAnalysis analysis = new IocConfigurationFailureAnalyzer()
                .analyze(new UnboundConfigurationPropertiesException(Set.of(property)));

        assertThat(analysis.getAction()).contains("CONFIG.LEGACY_SYNC_TIMEOUT");
    }

    @Test
    void recognizesLegacySmbEncryptionFromRawEnvironmentName() {
        ConfigurationProperty property = new ConfigurationProperty(
                ConfigurationPropertyName.of("ioc.sync.endpoints[0].smb.encrypt"),
                "ioc_sync_endpoints_0_smb_encrypt", null);
        FailureAnalysis analysis = new IocConfigurationFailureAnalyzer()
                .analyze(new UnboundConfigurationPropertiesException(Set.of(property)));

        assertThat(analysis.getAction()).contains("CONFIG.LEGACY_SMB_ENCRYPTION");
    }

    @Test
    void formatsOperatorDescriptionWithPlatformLineSeparators() {
        FailureAnalysis analysis = analyze("ioc.unknown.first", "ioc.unknown.second");

        assertThat(analysis.getDescription()).isEqualTo(String.join(System.lineSeparator(),
                "CONFIG.UNKNOWN_PROPERTY",
                "Unknown or removed IOC configuration keys were found:",
                "- ioc.unknown.first",
                "- ioc.unknown.second"));
    }

    private FailureAnalysis analyze(String... names) {
        Set<ConfigurationProperty> properties = java.util.Arrays.stream(names)
                .map(IocConfigurationFailureAnalyzerTest::property)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return new IocConfigurationFailureAnalyzer()
                .analyze(new UnboundConfigurationPropertiesException(properties));
    }

    private static ConfigurationProperty property(String name) {
        return new ConfigurationProperty(ConfigurationPropertyName.of(name), "value", null);
    }
}
