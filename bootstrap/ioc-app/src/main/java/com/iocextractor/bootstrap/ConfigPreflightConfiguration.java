package com.iocextractor.bootstrap;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.validation.Validator;

/**
 * Early configuration-properties validator wiring for the {@code ioc.*} root.
 */
@Configuration(proxyBeanMethods = false)
class ConfigPreflightConfiguration {

    @Bean(name = EnableConfigurationProperties.VALIDATOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Validator configurationPropertiesValidator() {
        return new IocConfigPreflight();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static IocUnknownConfigurationPreflight iocUnknownConfigurationPreflight(ConfigurableEnvironment environment) {
        return new IocUnknownConfigurationPreflight(environment);
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, EngineType> engineTypeConverter() {
        return new Converter<String, EngineType>() {
            @Override
            public EngineType convert(String source) {
                return EngineType.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, RuntimeMode> runtimeModeConverter() {
        return new Converter<String, RuntimeMode>() {
            @Override
            public RuntimeMode convert(String source) {
                return RuntimeMode.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ObservabilityMode> observabilityModeConverter() {
        return new Converter<String, ObservabilityMode>() {
            @Override
            public ObservabilityMode convert(String source) {
                return ObservabilityMode.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, StorageType> storageTypeConverter() {
        return new Converter<String, StorageType>() {
            @Override
            public StorageType convert(String source) {
                return StorageType.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ArtifactKeyMode> artifactKeyModeConverter() {
        return new Converter<String, ArtifactKeyMode>() {
            @Override
            public ArtifactKeyMode convert(String source) {
                return ArtifactKeyMode.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ArtifactIdStrategy> artifactIdStrategyConverter() {
        return new Converter<String, ArtifactIdStrategy>() {
            @Override
            public ArtifactIdStrategy convert(String source) {
                return ArtifactIdStrategy.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ExportTriggerType> exportTriggerTypeConverter() {
        return new Converter<String, ExportTriggerType>() {
            @Override
            public ExportTriggerType convert(String source) {
                return ExportTriggerType.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ExportOutputMode> exportOutputModeConverter() {
        return new Converter<String, ExportOutputMode>() {
            @Override
            public ExportOutputMode convert(String source) {
                return ExportOutputMode.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, IngestionLedgerType> ingestionLedgerTypeConverter() {
        return new Converter<String, IngestionLedgerType>() {
            @Override
            public IngestionLedgerType convert(String source) {
                return IngestionLedgerType.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, SyncTransport> syncTransportConverter() {
        return new Converter<String, SyncTransport>() {
            @Override
            public SyncTransport convert(String source) {
                return SyncTransport.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, RetentionActionType> retentionActionTypeConverter() {
        return new Converter<String, RetentionActionType>() {
            @Override
            public RetentionActionType convert(String source) {
                return RetentionActionType.parse(source);
            }
        };
    }
}
