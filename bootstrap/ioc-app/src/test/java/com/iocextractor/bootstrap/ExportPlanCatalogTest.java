package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ExportMode;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsUnknownOrDisabledArtifactBeforeInfrastructureIo() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Export invalid = export(properties,
                List.of(new IocProperties.Export.Profile("broken", "complete", List.of("missing"))));

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
                new IocProperties.Export.Profile("duplicate", "complete", List.of("masks")),
                new IocProperties.Export.Profile("duplicate", "complete", List.of("hashes"))));

        assertThatThrownBy(() -> catalog(withExport(properties, invalid), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate export profile");
    }

    @Test
    void rejectsAppendWithStableDiagnostic() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Export invalid = export(properties,
                List.of(new IocProperties.Export.Profile("append-profile", "append", List.of("masks"))));
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();

        assertThatThrownBy(() -> catalog(withExport(properties, invalid), diagnostics))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.UNSUPPORTED_MODE.id());
        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.UNSUPPORTED_MODE);
    }

    private ExportPlanCatalog catalog(IocProperties properties, ArrayList<Diagnostic> diagnostics) {
        Clock clock = Clock.systemUTC();
        return new ExportPlanCatalog(properties, diagnostics::add, new DiagnosticFactory(clock));
    }

    private IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        return new Binder(ConfigurationPropertySources.from(source))
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
                source.patterns(), source.classify(), source.sink(), source.lookup(), source.ingestion(),
                source.artifactIdentity(), export, source.maintenance(), source.observability());
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
                source.patterns(), source.classify(), sink, source.lookup(), source.ingestion(),
                source.artifactIdentity(), source.export(), source.maintenance(), source.observability());
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
