package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.in.export.ValidateExportProfileUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExportCommandTest {

    @Test
    void delegatesCompleteRecoveryAndExportUseCase() {
        List<String> calls = new ArrayList<>();
        ExportArtifactsUseCase exporter = command -> {
            calls.add("export:" + command.profile());
            return new ExportArtifactsResult("run-1", command.profile(),
                    ExportRunStatus.COMPLETED, "slice-1");
        };
        ExportCommand command = command(ignored -> calls.add("validate"), exporter);

        int exit = new CommandLine(command).execute("--profile", "reputation");

        assertThat(exit).isZero();
        assertThat(calls).containsExactly("validate", "export:reputation");
    }

    @Test
    void helpDoesNotResolveOrInvokeExportGraph() {
        AtomicInteger calls = new AtomicInteger();
        ExportArtifactsUseCase exporter = command -> {
            calls.incrementAndGet();
            return ExportArtifactsResult.unchanged(command.profile());
        };
        ExportCommand command = command(ignored -> calls.incrementAndGet(), exporter);

        int exit = new CommandLine(command).execute("--help");

        assertThat(exit).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnknownProfileBeforeResolvingExportGraph() {
        AtomicInteger resolutions = new AtomicInteger();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerBeanDefinition("exporter", new RootBeanDefinition(
                ExportArtifactsUseCase.class,
                () -> {
                    resolutions.incrementAndGet();
                    return (ExportArtifactsUseCase) command ->
                            ExportArtifactsResult.unchanged(command.profile());
                }));
        ExportCommand command = new ExportCommand(
                ignored -> {
                    throw new IllegalArgumentException("Unknown export profile: missing");
                },
                beans.getBeanProvider(ExportArtifactsUseCase.class),
                "test");

        int exit = new CommandLine(command).execute("--profile", "missing");

        assertThat(exit).isEqualTo(1);
        assertThat(resolutions).hasValue(0);
    }

    private ExportCommand command(ValidateExportProfileUseCase validator,
                                  ExportArtifactsUseCase exporter) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("exporter", exporter);
        return new ExportCommand(
                validator,
                beans.getBeanProvider(ExportArtifactsUseCase.class),
                "test");
    }
}
