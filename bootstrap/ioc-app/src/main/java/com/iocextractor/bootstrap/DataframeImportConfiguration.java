package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogCompiler;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/** Composition-root wiring for the disabled-by-default dataframe-import contract catalog. */
@Configuration(proxyBeanMethods = false)
class DataframeImportConfiguration {

    @Bean
    static DataframeImportCatalogCompiler dataframeImportCatalogCompiler() {
        return new DataframeImportCatalogCompiler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ioc.dataframe-import", name = "enabled", havingValue = "true")
    static DataframeImportCatalog dataframeImportCatalog(
            IocProperties properties,
            DataframeImportCatalogCompiler compiler) {
        return compiler.compile(
                DataframeImportPropertyMapper.draft(properties.dataframeImport()),
                DataframeImportPropertyMapper.environment(properties))
                .catalogOrThrow();
    }

    // Anonymous converters retain generic source/target metadata for the binder.
    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportSourceTransport> importSourceTransportConverter() {
        return new Converter<String, ImportSourceTransport>() {
            @Override
            public ImportSourceTransport convert(String source) {
                return ImportSourceTransport.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportMergePolicy> importMergePolicyConverter() {
        return new Converter<String, ImportMergePolicy>() {
            @Override
            public ImportMergePolicy convert(String source) {
                return ImportMergePolicy.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportProcessingMode> importProcessingModeConverter() {
        return new Converter<String, ImportProcessingMode>() {
            @Override
            public ImportProcessingMode convert(String source) {
                return ImportProcessingMode.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportRoutingPolicy> importRoutingPolicyConverter() {
        return new Converter<String, ImportRoutingPolicy>() {
            @Override
            public ImportRoutingPolicy convert(String source) {
                return ImportRoutingPolicy.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportRowFailurePolicy> importRowFailurePolicyConverter() {
        return new Converter<String, ImportRowFailurePolicy>() {
            @Override
            public ImportRowFailurePolicy convert(String source) {
                return ImportRowFailurePolicy.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportDuplicatePolicy> importDuplicatePolicyConverter() {
        return new Converter<String, ImportDuplicatePolicy>() {
            @Override
            public ImportDuplicatePolicy convert(String source) {
                return ImportDuplicatePolicy.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportFormulaPolicy> importFormulaPolicyConverter() {
        return new Converter<String, ImportFormulaPolicy>() {
            @Override
            public ImportFormulaPolicy convert(String source) {
                return ImportFormulaPolicy.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportRecordSeparator> importRecordSeparatorConverter() {
        return new Converter<String, ImportRecordSeparator>() {
            @Override
            public ImportRecordSeparator convert(String source) {
                return ImportRecordSeparator.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportArtifactRole> importArtifactRoleConverter() {
        return new Converter<String, ImportArtifactRole>() {
            @Override
            public ImportArtifactRole convert(String source) {
                return ImportArtifactRole.parse(source);
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ImportExistingSlotPolicy> importExistingSlotPolicyConverter() {
        return new Converter<String, ImportExistingSlotPolicy>() {
            @Override
            public ImportExistingSlotPolicy convert(String source) {
                return ImportExistingSlotPolicy.parse(source);
            }
        };
    }
}
