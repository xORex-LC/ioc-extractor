package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportPolicyToken;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import com.iocextractor.adapter.out.store.jdbc.SqliteTuningPreset;
import com.iocextractor.domain.classify.FeaturePredicates;
import com.iocextractor.domain.model.IndicatorType;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.validation.BeanPropertyBindingResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Protects the operator configuration surface from documentation/template
 * drift. The typed properties record remains the schema; this test makes every
 * newly added record component fail the build until both guide languages are
 * updated and verifies the shipped daemon template through the real binder and
 * semantic preflights. Guide checks prove structural coverage of property names
 * and closed vocabulary, not the semantic accuracy of prose, recommendations or
 * documented defaults; those remain review responsibilities.
 */
class ConfigurationDocumentationContractTest {

    private static final Pattern HINT_VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Test
    void everyTypedPropertyIsCoveredByBothOperatorGuides() {
        Set<String> paths = new LinkedHashSet<>();
        collectRecordPaths(IocProperties.class, "ioc", paths);

        String english = read(reactorRoot().resolve("docs/guides/configuration.md"));
        String russian = read(reactorRoot().resolve("docs/guides/ru/configuration.md"));

        assertThat(missingBacktickedTokens(english, paths))
                .as("typed ioc.* properties missing from the English operator guide")
                .isEmpty();
        assertThat(missingBacktickedTokens(russian, paths))
                .as("typed ioc.* properties missing from the Russian operator guide")
                .isEmpty();
    }

    @Test
    void productionTemplateBindsAndPassesConfigurationPreflights() throws Exception {
        IocProperties properties = bindProductionTemplate();

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(properties))
                    .as("Bean Validation violations in packaging/templates/application.yml")
                    .isEmpty();
        }

        var errors = new BeanPropertyBindingResult(properties, "ioc");
        new IocConfigPreflight().validate(properties, errors);
        assertThat(errors.getAllErrors())
                .as("semantic configuration errors in packaging/templates/application.yml")
                .isEmpty();
        assertThatNoException()
                .as("registry-backed values in packaging/templates/application.yml")
                .isThrownBy(() -> new ConfigRegistryPreflight(properties).afterPropertiesSet());
    }

    @Test
    void productionTemplateShowsEveryTypedPropertyField() {
        Set<String> paths = new LinkedHashSet<>();
        collectRecordPaths(IocProperties.class, "ioc", paths);
        Set<String> compatibilityAliases = new LinkedHashSet<>();
        collectCompatibilityAliases(IocProperties.class, "ioc", compatibilityAliases);
        assertThat(compatibilityAliases)
                .as("typed compatibility aliases must have migration-catalog entries")
                .containsExactlyInAnyOrderElementsOf(IocConfigurationMigrationCatalog.migrations().stream()
                        .map(IocConfigurationMigrationCatalog.Migration::propertyPath)
                        .toList());
        String template = read(reactorRoot().resolve("packaging/templates/application.yml"));

        List<String> missing = paths.stream()
                .filter(path -> !compatibilityAliases.contains(path))
                .filter(path -> !containsYamlField(template, terminalField(path)))
                .toList();

        assertThat(missing)
                .as("typed fields absent from the full production template")
                .isEmpty();
    }

    @Test
    void springValueHintsTrackBindableSelectorsAndMapKeys() {
        String metadata = readResource("META-INF/additional-spring-configuration-metadata.json");

        assertHint(metadata, "ioc.engine", selectorTokens(EngineType.values()));
        assertHint(metadata, "ioc.runtime.mode", selectorTokens(RuntimeMode.values()));
        assertHint(metadata, "ioc.storage.service.type", selectorTokens(StorageType.values()));
        assertHint(metadata, "ioc.storage.dataframe.type", selectorTokens(StorageType.values()));
        Set<String> sqlitePresets = new LinkedHashSet<>();
        Arrays.stream(SqliteTuningPreset.values()).map(SqliteTuningPreset::configValue).forEach(sqlitePresets::add);
        assertHint(metadata, "ioc.storage.service.sqlite.tuning", sqlitePresets);
        assertHint(metadata, "ioc.storage.dataframe.sqlite.tuning", sqlitePresets);
        assertHint(metadata, "ioc.observability.mode", selectorTokens(ObservabilityMode.values()));
        assertHint(metadata, "ioc.pipeline.failure-policy", Set.of("fail-fast", "collect-and-continue"));
        Set<String> indicatorTypes = new LinkedHashSet<>();
        Arrays.stream(IndicatorType.values()).map(Enum::name).forEach(indicatorTypes::add);
        assertHint(metadata, "ioc.patterns.keys", indicatorTypes);
        assertHint(metadata, "ioc.export.trigger.type", selectorTokens(ExportTriggerType.values()));
        assertHint(metadata, "ioc.ingestion.ledger.type", selectorTokens(IngestionLedgerType.values()));
        assertHint(metadata, "ioc.lifecycle.validity.mode", selectorTokens(LifecycleValidityMode.values()));
        assertHint(metadata, "ioc.lifecycle.validity.existing-records",
                selectorTokens(ExistingRecordsPolicy.values()));
        assertHint(metadata, "ioc.sync.endpoints[].smb.encryption",
                selectorTokens(SmbEncryptionMode.values()));
        assertThat(metadata)
                .contains("\"name\": \"ioc.sync.endpoints[].smb.encrypt\"")
                .contains("\"replacement\": \"ioc.sync.endpoints[].smb.encryption\"")
                .contains("\"level\": \"warning\"");
        assertHint(metadata, "ioc.dataframe-import.sources[].transport",
                importTokens(ImportSourceTransport.values()));
        assertHint(metadata, "ioc.dataframe-import.authority-profiles[].maximum-merge-policy",
                importTokens(ImportMergePolicy.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].mode",
                importTokens(ImportProcessingMode.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].routing",
                importTokens(ImportRoutingPolicy.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].row-failure-policy",
                importTokens(ImportRowFailurePolicy.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].duplicate-policy",
                importTokens(ImportDuplicatePolicy.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].formula-policy",
                importTokens(ImportFormulaPolicy.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].dialect.record-separator",
                importTokens(ImportRecordSeparator.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].artifacts[].role",
                importTokens(ImportArtifactRole.values()));
        assertHint(metadata, "ioc.dataframe-import.contracts[].requested-slot.existing-record-policy",
                importTokens(ImportExistingSlotPolicy.values()));
        assertHint(metadata, "ioc.dataframe-import.runtime.retention.successful.action",
                selectorTokens(RetentionActionType.values()));
        assertHint(metadata, "ioc.dataframe-import.runtime.retention.unsuccessful.action",
                selectorTokens(RetentionActionType.values()));
        assertHint(metadata, "ioc.source.charset", Set.of("auto"));
        assertThat(hintBlock(metadata, "ioc.source.charset"))
                .contains("handle-as", "java.nio.charset.Charset");
        assertThat(hintBlock(metadata, "ioc.sink.csv.charset"))
                .contains("handle-as", "java.nio.charset.Charset");
    }

    @Test
    void nestedCollectionVocabulariesAreCoveredByBothOperatorGuides() {
        Set<String> values = new LinkedHashSet<>();
        values.addAll(FeaturePredicates.defaults().keySet());
        values.addAll(ConfigRegistryCatalog.artifactFilterKeys());
        values.addAll(ConfigRegistryCatalog.valueProviderKeys());
        values.add(ConfigRegistryCatalog.CONST_VALUE_PROVIDER);
        values.addAll(ConfigRegistryCatalog.transformKeys());
        Arrays.stream(IndicatorType.values()).map(Enum::name).forEach(values::add);
        addSelectorTokens(values, ArtifactIdStrategy.values());
        addSelectorTokens(values, ArtifactKeyMode.values());
        addSelectorTokens(values, ExportOutputMode.values());
        addSelectorTokens(values, SyncTransport.values());
        addSelectorTokens(values, SmbEncryptionMode.values());
        addSelectorTokens(values, RetentionActionType.values());
        addImportTokens(values, ImportSourceTransport.values());
        addImportTokens(values, ImportMergePolicy.values());
        addImportTokens(values, ImportProcessingMode.values());
        addImportTokens(values, ImportRoutingPolicy.values());
        addImportTokens(values, ImportRowFailurePolicy.values());
        addImportTokens(values, ImportDuplicatePolicy.values());
        addImportTokens(values, ImportFormulaPolicy.values());
        addImportTokens(values, ImportRecordSeparator.values());
        addImportTokens(values, ImportArtifactRole.values());
        addImportTokens(values, ImportExistingSlotPolicy.values());

        String english = read(reactorRoot().resolve("docs/guides/configuration.md"));
        String russian = read(reactorRoot().resolve("docs/guides/ru/configuration.md"));
        assertThat(missingBacktickedTokens(english, values))
                .as("registry/selector values missing from the English operator guide")
                .isEmpty();
        assertThat(missingBacktickedTokens(russian, values))
                .as("registry/selector values missing from the Russian operator guide")
                .isEmpty();
    }

    @Test
    void vocabularyCoverageRequiresExactBacktickedTokens() {
        String proseWithAccidentalMatches = "deleted over smb-server";

        assertThat(missingBacktickedTokens(proseWithAccidentalMatches, Set.of("delete", "smb")))
                .containsExactlyInAnyOrder("delete", "smb");
        assertThat(missingBacktickedTokens("use `delete` over `smb`", Set.of("delete", "smb")))
                .isEmpty();
    }

    private IocProperties bindProductionTemplate() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var sources = new MutablePropertySources();
        Path template = reactorRoot().resolve("packaging/templates/application.yml");
        sources.addFirst(loader.load("production-template", new FileSystemResource(template)).getFirst());
        sources.addLast(loader.load("packaged-defaults", new ClassPathResource("application.yml")).getFirst());

        ApplicationConversionService conversionService = new ApplicationConversionService();
        conversionService.addConverter(String.class, IdStart.class, IdStart::parse);
        conversionService.addConverter(Number.class, IdStart.class, IdStart::from);
        return new Binder(ConfigurationPropertySources.from(sources), null, conversionService)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("production template did not bind to IocProperties"));
    }

    private static void collectRecordPaths(Class<?> recordType, String prefix, Set<String> paths) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            String path = prefix + "." + kebabCase(component.getName());
            Class<?> componentType = component.getType();
            Type genericType = component.getGenericType();
            if (componentType.isRecord()) {
                collectRecordPaths(componentType, path, paths);
            } else if (Collection.class.isAssignableFrom(componentType)
                    && genericType instanceof ParameterizedType parameterized) {
                paths.add(path);
                Type element = parameterized.getActualTypeArguments()[0];
                if (element instanceof Class<?> elementClass && elementClass.isRecord()) {
                    collectRecordPaths(elementClass, path + "[]", paths);
                }
            } else {
                paths.add(path);
            }
        }
    }

    private static void collectCompatibilityAliases(Class<?> recordType, String prefix, Set<String> paths) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            String path = prefix + "." + kebabCase(component.getName());
            Class<?> componentType = component.getType();
            Type genericType = component.getGenericType();
            if (component.isAnnotationPresent(ConfigurationCompatibilityAlias.class)) {
                paths.add(path);
            }
            if (componentType.isRecord()) {
                collectCompatibilityAliases(componentType, path, paths);
            } else if (Collection.class.isAssignableFrom(componentType)
                    && genericType instanceof ParameterizedType parameterized) {
                Type element = parameterized.getActualTypeArguments()[0];
                if (element instanceof Class<?> elementClass && elementClass.isRecord()) {
                    collectCompatibilityAliases(elementClass, path + "[]", paths);
                }
            }
        }
    }

    private static List<String> missingBacktickedTokens(String guide, Collection<String> tokens) {
        return tokens.stream()
                .filter(token -> !guide.contains("`" + token + "`"))
                .toList();
    }

    private static String kebabCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static String terminalField(String path) {
        int separator = path.lastIndexOf('.');
        return path.substring(separator + 1).replace("[]", "");
    }

    private static boolean containsYamlField(String yaml, String field) {
        return Pattern.compile("(?m)(?:^\\s*#?\\s*(?:-\\s*)?|[,{]\\s*)"
                        + Pattern.quote(field) + "\\s*:")
                .matcher(yaml)
                .find();
    }

    private static Set<String> selectorTokens(ConfigSelector[] selectors) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(selectors).map(ConfigSelector::token).forEach(result::add);
        return result;
    }

    private static void addSelectorTokens(Set<String> target, ConfigSelector[] selectors) {
        target.addAll(selectorTokens(selectors));
    }

    private static Set<String> importTokens(ImportPolicyToken[] selectors) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(selectors).map(ImportPolicyToken::token).forEach(result::add);
        return result;
    }

    private static void addImportTokens(Set<String> target, ImportPolicyToken[] selectors) {
        target.addAll(importTokens(selectors));
    }

    private static void assertHint(String metadata, String name, Collection<String> expectedValues) {
        String block = hintBlock(metadata, name);
        Set<String> actual = new LinkedHashSet<>();
        Matcher matcher = HINT_VALUE.matcher(block);
        while (matcher.find()) {
            actual.add(matcher.group(1));
        }
        assertThat(actual).as("values for Spring hint %s", name)
                .containsExactlyInAnyOrderElementsOf(expectedValues);
    }

    private static String hintBlock(String metadata, String name) {
        String marker = "\"name\": \"" + name + "\"";
        int start = metadata.indexOf(marker);
        assertThat(start).as("Spring value hint %s", name).isNotNegative();
        int next = metadata.indexOf("\n    {\n      \"name\":", start + marker.length());
        return metadata.substring(start, next < 0 ? metadata.length() : next);
    }

    private static String readResource(String name) {
        try (var input = ConfigurationDocumentationContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("resource not found: " + name);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path reactorRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("platform"))
                    && Files.isDirectory(dir.resolve("core"))
                    && Files.isDirectory(dir.resolve("adapters"))
                    && Files.isDirectory(dir.resolve("bootstrap"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("reactor root not found");
    }
}
