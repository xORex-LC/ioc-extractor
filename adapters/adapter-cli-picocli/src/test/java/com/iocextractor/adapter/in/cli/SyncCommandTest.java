package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.port.in.sync.ValidateSyncSelectionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SyncCommandTest {

    @Test
    void fetchForwardsFiltersAndRendersDryRunCounters() {
        RecordingValidator validator = new RecordingValidator();
        List<RemoteFetchCommand> commands = new ArrayList<>();
        RemoteFetchUseCase fetcher = command -> {
            commands.add(command);
            return new RemoteFetchResult(2, 3, 0);
        };
        SyncFetchCommand command = new SyncFetchCommand(validator, provider(RemoteFetchUseCase.class, fetcher));
        StringWriter output = new StringWriter();

        int exit = commandLine(command, output)
                .execute("--source", "incoming", "--endpoint", "share", "--dry-run");

        assertThat(exit).isZero();
        assertThat(validator.calls).containsExactly("fetch:incoming:share:true");
        assertThat(commands).singleElement().satisfies(actual -> {
            assertThat(actual.source()).contains("incoming");
            assertThat(actual.endpoint()).contains("share");
            assertThat(actual.dryRun()).isTrue();
        });
        assertThat(output.toString()).contains("Fetch dry-run: fetched=2 skipped=3 failed=0");
    }

    @Test
    void publishReturnsFailureExitCodeAndRendersCounters() {
        RecordingValidator validator = new RecordingValidator();
        ArtifactPublishUseCase publisher = publisherReturning(new ArtifactPublishResult(1, 2, 1, 0));
        SyncPublishCommand command = new SyncPublishCommand(
                validator, provider(ArtifactPublishUseCase.class, publisher));
        StringWriter output = new StringWriter();

        int exit = commandLine(command, output)
                .execute("--profile", "reputation", "--target", "backup", "--endpoint", "share");

        assertThat(exit).isOne();
        assertThat(validator.calls).containsExactly("publish:reputation:backup:share:false");
        assertThat(output.toString()).contains("Publish: pending=1 succeeded=2 failed=1 abandoned=0");
    }

    @Test
    void helpDoesNotValidateOrResolveFetchGraph() {
        AtomicInteger resolutions = new AtomicInteger();
        DefaultListableBeanFactory beans = lazyFetchBeans(resolutions);
        RecordingValidator validator = new RecordingValidator();
        SyncFetchCommand command = new SyncFetchCommand(
                validator, beans.getBeanProvider(RemoteFetchUseCase.class));

        int exit = commandLine(command, new StringWriter()).execute("--help");

        assertThat(exit).isZero();
        assertThat(validator.calls).isEmpty();
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void invalidSelectionFailsBeforeResolvingFetchGraph() {
        AtomicInteger resolutions = new AtomicInteger();
        DefaultListableBeanFactory beans = lazyFetchBeans(resolutions);
        ValidateSyncSelectionUseCase validator = new RecordingValidator() {
            @Override
            public void validateFetch(RemoteFetchCommand command) {
                throw new IllegalArgumentException("Unknown sync endpoint: missing");
            }
        };
        SyncFetchCommand command = new SyncFetchCommand(
                validator, beans.getBeanProvider(RemoteFetchUseCase.class));

        int exit = commandLine(command, new StringWriter()).execute("--endpoint", "missing");

        assertThat(exit).isOne();
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void allValidatesBothSelectionsBeforeResolvingOrExecutingEitherUseCase() {
        AtomicInteger fetchResolutions = new AtomicInteger();
        AtomicInteger publishResolutions = new AtomicInteger();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerBeanDefinition("fetcher", new RootBeanDefinition(RemoteFetchUseCase.class, () -> {
            fetchResolutions.incrementAndGet();
            return (RemoteFetchUseCase) ignored -> new RemoteFetchResult(1, 0, 0);
        }));
        beans.registerBeanDefinition("publisher", new RootBeanDefinition(ArtifactPublishUseCase.class, () -> {
            publishResolutions.incrementAndGet();
            return publisherReturning(new ArtifactPublishResult(0, 1, 0, 0));
        }));
        RecordingValidator validator = new RecordingValidator() {
            @Override
            public void validatePublish(ArtifactPublishCommand command) {
                super.validatePublish(command);
                throw new IllegalArgumentException("Unknown sync publish profile: missing");
            }
        };
        SyncAllCommand command = new SyncAllCommand(
                validator,
                beans.getBeanProvider(RemoteFetchUseCase.class),
                beans.getBeanProvider(ArtifactPublishUseCase.class));

        int exit = commandLine(command, new StringWriter())
                .execute("--source", "incoming", "--profile", "missing");

        assertThat(exit).isOne();
        assertThat(validator.calls).containsExactly(
                "fetch:incoming:*:false", "publish:missing:*:*:false");
        assertThat(fetchResolutions).hasValue(0);
        assertThat(publishResolutions).hasValue(0);
    }

    @Test
    void allExecutesFetchBeforePublishAfterSuccessfulPreflight() {
        RecordingValidator validator = new RecordingValidator();
        List<String> calls = new ArrayList<>();
        RemoteFetchUseCase fetcher = command -> {
            calls.add("fetch");
            return new RemoteFetchResult(1, 0, 0);
        };
        ArtifactPublishUseCase publisher = new ArtifactPublishUseCase() {
            @Override
            public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
                throw new UnsupportedOperationException("reconcile is not used by CLI");
            }

            @Override
            public ArtifactPublishResult publish(ArtifactPublishCommand command) {
                calls.add("publish");
                return new ArtifactPublishResult(0, 1, 0, 0);
            }
        };
        SyncAllCommand command = new SyncAllCommand(
                validator,
                provider(RemoteFetchUseCase.class, fetcher),
                provider(ArtifactPublishUseCase.class, publisher));

        int exit = commandLine(command, new StringWriter()).execute();

        assertThat(exit).isZero();
        assertThat(validator.calls).containsExactly("fetch:*:*:false", "publish:*:*:*:false");
        assertThat(calls).containsExactly("fetch", "publish");
    }

    private CommandLine commandLine(Object command, StringWriter output) {
        return new CommandLine(command)
                .setOut(new PrintWriter(output, true))
                .setErr(new PrintWriter(output, true));
    }

    private DefaultListableBeanFactory lazyFetchBeans(AtomicInteger resolutions) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerBeanDefinition("fetcher", new RootBeanDefinition(RemoteFetchUseCase.class, () -> {
            resolutions.incrementAndGet();
            return (RemoteFetchUseCase) ignored -> new RemoteFetchResult(0, 0, 0);
        }));
        return beans;
    }

    private ArtifactPublishUseCase publisherReturning(ArtifactPublishResult result) {
        return new ArtifactPublishUseCase() {
            @Override
            public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
                return result;
            }

            @Override
            public ArtifactPublishResult publish(ArtifactPublishCommand command) {
                return result;
            }
        };
    }

    private <T> org.springframework.beans.factory.ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(type.getName(), bean);
        return beans.getBeanProvider(type);
    }

    private static class RecordingValidator implements ValidateSyncSelectionUseCase {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void validateFetch(RemoteFetchCommand command) {
            calls.add("fetch:" + command.source().orElse("*") + ":"
                    + command.endpoint().orElse("*") + ":" + command.dryRun());
        }

        @Override
        public void validatePublish(ArtifactPublishCommand command) {
            calls.add("publish:" + command.profile().orElse("*") + ":"
                    + command.target().orElse("*") + ":"
                    + command.endpoint().orElse("*") + ":" + command.dryRun());
        }
    }
}
