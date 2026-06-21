package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RecoverIngestionUseCase;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Spring Integration wiring for daemon-mode file discovery.
 */
@Configuration
@EnableIntegration
@EnableConfigurationProperties(IngestAdapterProperties.class)
@ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
public class IngestFlowConfiguration {

    @Bean
    public Clock ingestClock() {
        return Clock.systemUTC();
    }

    @Bean
    public FileSourceHasher fileSourceHasher() {
        return new FileSourceHasher();
    }

    @Bean
    public SourceLifecycle sourceLifecycle(IngestAdapterProperties properties) {
        return new FileSystemSourceLifecycle(
                Path.of(properties.dirs().processing()),
                Path.of(properties.dirs().done()),
                Path.of(properties.dirs().failed()));
    }

    @Bean
    public IngestionLedger ingestionLedger(IngestAdapterProperties properties, Clock ingestClock) {
        return new FileIngestionLedger(Path.of(properties.ledger().path()), ingestClock);
    }

    @Bean
    public FileSourceMessageHandler fileSourceMessageHandler(FileSourceHasher hasher,
                                                             IngestSourceUseCase useCase,
                                                             IngestAdapterProperties properties,
                                                             Clock ingestClock) {
        return new FileSourceMessageHandler(hasher, useCase, ingestClock,
                properties.retry().maxAttempts(), properties.retry().backoff());
    }

    @Bean
    public ApplicationRunner ingestionRecoveryRunner(RecoverIngestionUseCase useCase) {
        return new IngestionRecoveryRunner(useCase);
    }

    @Bean
    public IntegrationFlow iocIngestionFlow(IngestAdapterProperties properties,
                                            FileSourceMessageHandler handler,
                                            Clock ingestClock) {
        FileReadingMessageSource source = new FileReadingMessageSource();
        source.setDirectory(ensureDirectory(Path.of(properties.dirs().inbox())).toFile());
        source.setUseWatchService(properties.detect().useWatchService());
        source.setFilter(new IngestFileListFilter(
                properties.patterns().include(),
                properties.patterns().exclude(),
                properties.stability().quietPeriod(),
                ingestClock));

        Duration interval = properties.detect().reconcileInterval();
        return IntegrationFlow.from(source, spec -> spec.poller(Pollers
                        .fixedDelay(interval)
                        .maxMessagesPerPoll(properties.detect().maxMessagesPerPoll())))
                .handle(handler, "handle")
                .get();
    }

    private Path ensureDirectory(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create ingestion directory: " + directory, e);
        }
    }
}
