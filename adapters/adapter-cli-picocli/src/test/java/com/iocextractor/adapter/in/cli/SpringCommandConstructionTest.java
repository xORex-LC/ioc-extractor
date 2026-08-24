package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.ValidateSyncSelectionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class SpringCommandConstructionTest {

    @Test
    void springSelectsRuntimeConstructorsInsteadOfMetadataShells() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ValidateSyncSelectionUseCase.class, NoopSyncValidator::new);
            context.register(
                    ImportValidateCommand.class,
                    ImportStatusCommand.class,
                    ImportReplayCommand.class,
                    SyncFetchCommand.class,
                    SyncPublishCommand.class,
                    SyncAllCommand.class);
            context.refresh();

            assertThat(context.getBean(ImportValidateCommand.class))
                    .extracting("validators", "locators")
                    .doesNotContainNull();
            assertThat(context.getBean(ImportStatusCommand.class))
                    .extracting("statusUseCases")
                    .isNotNull();
            assertThat(context.getBean(ImportReplayCommand.class))
                    .extracting("replayUseCases")
                    .isNotNull();
            assertThat(context.getBean(SyncFetchCommand.class))
                    .extracting("validator", "useCase")
                    .doesNotContainNull();
            assertThat(context.getBean(SyncPublishCommand.class))
                    .extracting("validator", "useCase")
                    .doesNotContainNull();
            assertThat(context.getBean(SyncAllCommand.class))
                    .extracting("validator", "fetchUseCase", "publishUseCase")
                    .doesNotContainNull();
        }
    }

    private static final class NoopSyncValidator implements ValidateSyncSelectionUseCase {
        @Override
        public void validateFetch(RemoteFetchCommand command) {
            // The construction contract does not execute a remote operation.
        }

        @Override
        public void validatePublish(ArtifactPublishCommand command) {
            // The construction contract does not execute a remote operation.
        }
    }
}
