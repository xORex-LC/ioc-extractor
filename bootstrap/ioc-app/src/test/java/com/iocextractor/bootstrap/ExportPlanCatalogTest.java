package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ExportMode;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ExportPlanCatalogTest {

    @Test
    void resolvesDefaultProfilesWithOrderedSchemasAndIdentity() throws Exception {
        IocProperties properties = defaults();

        ExportPlanCatalog catalog = catalog(properties, new ArrayList<>());

        assertThat(catalog.plans()).extracting(plan -> plan.profile().name())
                .containsExactly("reputation-lists", "address-blacklist");
        assertThat(catalog.plans().getFirst().profile().mode()).isEqualTo(ExportMode.COMPLETE);
        assertThat(catalog.plans().getFirst().artifacts()).extracting("artifactName")
                .containsExactly("masks", "ip_list", "hashes");
        assertThat(catalog.plans().getFirst().artifacts()).allSatisfy(artifact -> {
            assertThat(artifact.identityHash()).hasSize(64);
            assertThat(artifact.schemaHash()).hasSize(64);
        });
        catalog.requireProfile("reputation-lists");
        assertThatThrownBy(() -> catalog.requireProfile("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown export profile");
    }

    @Test
    void activeMappingChangeInvalidatesPlanWithoutChangingPublicSchema() throws Exception {
        IocProperties properties = defaults();
        var original = catalog(properties, new ArrayList<>()).plans().getFirst();
        IocProperties.Sink.Artifact masks = properties.sink().artifacts().getFirst();
        List<IocProperties.Sink.Artifact.Column> columns = new ArrayList<>(masks.columns());
        IocProperties.Sink.Artifact.Column mask = columns.get(1);
        columns.set(1, new IocProperties.Sink.Artifact.Column(
                mask.name(), mask.from(), mask.value(), mask.type(), mask.whenType(), List.of("upper")));
        IocProperties.Sink.Artifact changed = copyArtifact(masks, true, columns);

        var revised = catalog(withSinkArtifact(properties, changed), new ArrayList<>())
                .plans().getFirst();

        assertThat(revised.artifacts().getFirst().schemaHash())
                .isEqualTo(original.artifacts().getFirst().schemaHash());
        assertThat(revised.artifacts().getFirst().mappingHash())
                .isNotEqualTo(original.artifacts().getFirst().mappingHash());
        assertThat(revised.planHash()).isNotEqualTo(original.planHash());
    }

    @Test
    void defaultExportFingerprintsStayWireCompatible() throws Exception {
        IocProperties properties = defaults();
        var plans = catalog(properties, new ArrayList<>()).plans();

        assertThat(plans)
                .extracting(plan -> plan.profile().name(), plan -> plan.planHash())
                .containsExactly(
                        tuple("reputation-lists",
                                "389252b9da27e14b1477ac82bb84734c09271ec4a85ed35289390ed4828f410a"),
                        tuple("address-blacklist",
                                "2757f361b6338b8ec0957e8c0ae637ed1b5673441955583f3b97815c70756eea"));
        assertThat(plans.getFirst().artifacts())
                .extracting(artifact -> artifact.artifactName(), artifact -> artifact.mappingHash())
                .containsExactly(
                        tuple("masks", "d2b6f8f0d0c0a67e1316f02af70c0162c99b73851010e74ba89295d7c30083e5"),
                        tuple("ip_list", "bc91f31706aa8d264e3700d19bf12c5fd310e23d6b81829cfd7af3ba6c7deca1"),
                        tuple("hashes", "0fbcbbe5d6ca875407553d86ae7d85c7e8f52accf50517f72f997a4d337556f1"));
        assertThat(plans.get(1).artifacts())
                .extracting(artifact -> artifact.artifactName(), artifact -> artifact.mappingHash())
                .containsExactly(tuple("address_blacklist",
                        "7117767f5770e482e970835fac62db5d82389d9924141a204fb7eae7e6730553"));
    }

    @Test
    void mappingHashNormalizesAutoIdStart() throws Exception {
        IocProperties properties = defaults();
        var original = catalog(properties, new ArrayList<>()).plans().getFirst();
        IocProperties.Sink.Artifact masks = properties.sink().artifacts().getFirst();
        IocProperties.Sink.Artifact.Id id = masks.id();
        IocProperties.Sink.Artifact normalized = new IocProperties.Sink.Artifact(
                masks.name(), masks.enabled(), masks.path(), masks.accepts(), masks.include(),
                masks.exclude(), new IocProperties.Sink.Artifact.Id(id.strategy(), IdStart.parse(" AUTO ")),
                masks.columns());

        var revised = catalog(withSinkArtifact(properties, normalized), new ArrayList<>())
                .plans().getFirst();

        assertThat(revised.artifacts().getFirst().mappingHash())
                .isEqualTo(original.artifacts().getFirst().mappingHash());
        assertThat(revised.planHash()).isEqualTo(original.planHash());
    }


    @Test
    void rejectsUnknownOrDisabledArtifactBeforeInfrastructureIo() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Export invalid = export(properties,
                List.of(new IocProperties.Export.Profile("broken", ExportOutputMode.COMPLETE, List.of("missing"))));

        assertThatThrownBy(() -> catalog(withExport(properties, invalid), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown or disabled");
    }

    @Test
    void rejectsDisabledArtifactBeforeInfrastructureIo() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Sink.Artifact masks = properties.sink().artifacts().getFirst();

        assertThatThrownBy(() -> catalog(
                withSinkArtifact(properties, copyArtifact(masks, false, masks.columns())),
                new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown or disabled");
    }

    @Test
    void rejectsDuplicateProfileNames() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Export invalid = export(properties, List.of(
                new IocProperties.Export.Profile("duplicate", ExportOutputMode.COMPLETE, List.of("masks")),
                new IocProperties.Export.Profile("duplicate", ExportOutputMode.COMPLETE, List.of("hashes"))));

        assertThatThrownBy(() -> catalog(withExport(properties, invalid), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate export profile");
    }

    @Test
    void rejectsAppendWithStableDiagnostic() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Export invalid = export(properties,
                List.of(new IocProperties.Export.Profile("append-profile", ExportOutputMode.APPEND, List.of("masks"))));
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();

        assertThatThrownBy(() -> catalog(withExport(properties, invalid), diagnostics))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.UNSUPPORTED_MODE.id());
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(ExportDiagnosticCodes.UNSUPPORTED_MODE);
            assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.FATAL);
        });
    }

    @Test
    void rejectsInvalidCsvFormatBeforeInfrastructureIo() throws Exception {
        IocProperties properties = defaults();

        assertThatThrownBy(() -> catalog(withCsv(properties,
                new IocProperties.Sink.Csv(";", "\"", "NULL", "not-a-charset")), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported export charset");
        assertThatThrownBy(() -> catalog(withCsv(properties,
                new IocProperties.Sink.Csv(";;", "\"", "NULL", "UTF-8")), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be one character");
        assertThatThrownBy(() -> catalog(withCsv(properties,
                new IocProperties.Sink.Csv(";", ";", "NULL", "UTF-8")), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be distinct");
    }

    @Test
    void rejectsReservedSliceFileNameBeforeInfrastructureIo() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Sink.Artifact masks = properties.sink().artifacts().getFirst();
        IocProperties.Sink.Artifact reserved = new IocProperties.Sink.Artifact(
                masks.name(), masks.enabled(), "./dataframe/manifest.json", masks.accepts(),
                masks.include(), masks.exclude(), masks.id(), masks.columns());

        assertThatThrownBy(() -> catalog(withSinkArtifact(properties, reserved), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved slice file name");
    }

    private ExportPlanCatalog catalog(IocProperties properties, ArrayList<Diagnostic> diagnostics) {
        Clock clock = Clock.systemUTC();
        return new ExportPlanCatalog(properties, diagnostics::add, new DiagnosticFactory(clock));
    }

    private IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        ApplicationConversionService conversionService = new ApplicationConversionService();
        conversionService.addConverter(String.class, IdStart.class, IdStart::parse);
        conversionService.addConverter(Number.class, IdStart.class, IdStart::from);
        return new Binder(ConfigurationPropertySources.from(source), null, conversionService)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }

    private IocProperties.Export export(IocProperties properties,
                                        List<IocProperties.Export.Profile> profiles) {
        return new IocProperties.Export(
                properties.export().enabled(), properties.export().root(),
                properties.export().trigger(), profiles, properties.export().retention());
    }

    private IocProperties withExport(IocProperties source, IocProperties.Export export) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), source.sink(), source.pipeline(), source.ingestion(),
                source.artifactIdentity(), export, source.sync(), source.maintenance(), source.observability());
    }

    private IocProperties withSinkArtifact(IocProperties source, IocProperties.Sink.Artifact replacement) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        int index = java.util.stream.IntStream.range(0, artifacts.size())
                .filter(candidate -> artifacts.get(candidate).name().equals(replacement.name()))
                .findFirst().orElseThrow();
        artifacts.set(index, replacement);
        IocProperties.Sink sink = new IocProperties.Sink(source.sink().csv(), artifacts);
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), sink, source.pipeline(), source.ingestion(),
                source.artifactIdentity(), source.export(), source.sync(), source.maintenance(), source.observability());
    }

    private IocProperties withCsv(IocProperties source, IocProperties.Sink.Csv csv) {
        IocProperties.Sink sink = new IocProperties.Sink(csv, source.sink().artifacts());
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), sink, source.pipeline(), source.ingestion(),
                source.artifactIdentity(), source.export(), source.sync(), source.maintenance(), source.observability());
    }

    private IocProperties.Sink.Artifact copyArtifact(
            IocProperties.Sink.Artifact source,
            boolean enabled,
            List<IocProperties.Sink.Artifact.Column> columns) {
        return new IocProperties.Sink.Artifact(
                source.name(), enabled, source.path(), source.accepts(), source.include(),
                source.exclude(), source.id(), columns);
    }
}
