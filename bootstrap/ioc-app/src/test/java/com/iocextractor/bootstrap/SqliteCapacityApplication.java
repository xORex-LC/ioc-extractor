package com.iocextractor.bootstrap;

import com.iocextractor.IocExtractorApplication;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaPlan;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactProjectionWorkStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcDocumentPreparationWorkspaceFactory;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleActivationStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleClock;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleControlStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import com.iocextractor.adapter.out.store.jdbc.StagedCapacityBundle;
import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.application.artifact.lifecycle.EventPublishingCanonicalArtifactWriter;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.dataframeimport.EventPublishingCanonicalImportWriter;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** Isolated full-service launcher; native and staged use the same packaged application classes. */
public final class SqliteCapacityApplication {
    private SqliteCapacityApplication() { }

    public static void main(String[] arguments) {
        String backend = System.getProperty("qualification.sqlite");
        if (!"native".equals(backend) && !"staged".equals(backend)) {
            throw new IllegalArgumentException("qualification.sqlite must be native or staged");
        }
        SpringApplication.run(new Class<?>[]{IocExtractorApplication.class,
                CandidateConfiguration.class, SqliteCapacityMetrics.Endpoints.class}, arguments);
    }

    /** Explicit test composition, absent from the bootable JAR and selected only in qualification forks. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "qualification.sqlite", havingValue = "staged")
    public static class CandidateConfiguration {
        @Bean(destroyMethod = "close")
        StagedCapacityBundle qualificationStagedBundle(IocProperties props, AppConfig ignored,
                @org.springframework.beans.factory.annotation.Qualifier("dataframeStorageDataSource") DataSource data,
                @org.springframework.beans.factory.annotation.Qualifier("serviceStorageDataSource") DataSource service,
                DataframeSchemaPlan dataframeSchemaReconciliation, ArtifactIdBaseline baseline,
                @org.springframework.beans.factory.annotation.Qualifier("artifactIdentityValidation") java.util.List<?> validated,
                JdbcLifecycleClock time, Clock clock, JdbcWriterAdmission writers,
                JdbcDocumentPreparationWorkspaceFactory workspaces, DiagnosticSink diagnostics) throws Exception {
            // Direct use of the existing bootstrap builders avoids a circular staged-port dependency during native activation.
            var config = new AppConfig();
            var schemas = config.dataframeSchemas(props);
            var identities = config.artifactIdentityDefinitions(props);
            var control = new JdbcLifecycleControlStore(data, schemas);
            var projectionState = new JdbcArtifactProjectionWorkStore(data, clock, writers);
            var repository = new JdbcCanonicalArtifactRepository(data, schemas,
                    new com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver(identities), clock, time, writers);
            var projection = config.artifactProjection(repository, projectionState, props, clock);
            config.resumeLifecycleActivationUseCase(control, new JdbcLifecycleActivationStore(data, schemas, clock),
                    config.convergeArtifactProjectionsUseCase(projectionState, projection, diagnostics, props), time, props).resume();
            return new StagedCapacityBundle(new StagedCapacityBundle.Inputs(data, schemas,
                    config.artifactIdAllocatorDefinitions(props, baseline), identities, time, clock,
                    props.lifecycle().validity().fixedTtl(), writers, workspaces,
                    Path.of(props.dataframeImport().runtime().dirs().staging()), ArtifactPolicyCatalog.compile(props),
                    props.lifecycle().receiptRetention()));
        }

        @Bean
        static BeanFactoryPostProcessor qualificationPorts() {
            return factory -> {
                var registry = (BeanDefinitionRegistry) factory;
                Map<String, Class<?>> ports = Map.of(
                        "canonicalArtifactWriter", CanonicalArtifactWriter.class,
                        "canonicalImportWriter", CanonicalImportWriter.class,
                        "expiredArtifactStore", ExpiredArtifactStore.class,
                        "artifactProjectionWorkStore", ArtifactProjectionWorkStore.class,
                        "lifecycleHistoryStore", LifecycleHistoryStore.class,
                        "confirmationReceiptStore", StagedCapacityBundle.receiptType(),
                        "dataframeImportCommitEvidenceStore", ImportCommitEvidenceStore.class,
                        "snapshotSliceReader", JdbcSnapshotSliceReader.class);
                ports.forEach((name, type) -> {
                    if (!registry.containsBeanDefinition(name)) {
                        if (name.equals("canonicalImportWriter") || name.equals("dataframeImportCommitEvidenceStore")) {
                            return; // Import ports are absent when the normal import feature is disabled.
                        }
                        throw new IllegalStateException("Native qualification port missing: " + name);
                    }
                    registry.removeBeanDefinition(name);
                    var definition = new RootBeanDefinition(type);
                    definition.setDependsOn("qualificationStagedBundle");
                    definition.setInstanceSupplier(() -> {
                        Object value = factory.getBean(StagedCapacityBundle.class).port(name);
                        var events = factory.getBean(ControlEventPublisher.class);
                        if (value instanceof CanonicalArtifactWriter writer) {
                            return new EventPublishingCanonicalArtifactWriter(writer, events);
                        }
                        if (value instanceof CanonicalImportWriter writer) {
                            return new EventPublishingCanonicalImportWriter(writer, events);
                        }
                        return value;
                    });
                    registry.registerBeanDefinition(name, definition);
                });
            };
        }
    }
}
