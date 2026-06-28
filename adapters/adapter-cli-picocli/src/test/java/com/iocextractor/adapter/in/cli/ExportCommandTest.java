package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.in.export.RecoverExportUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExportCommandTest {

    @Test
    void invokesRecoveryBeforeOnDemandExport() {
        List<String> calls = new ArrayList<>();
        RecoverExportUseCase recovery = () -> {
            calls.add("recover");
            return 1;
        };
        ExportArtifactsUseCase exporter = command -> {
            calls.add("export:" + command.profile());
            return new ExportArtifactsResult("run-1", command.profile(),
                    ExportRunStatus.COMPLETED, "slice-1");
        };
        ExportCommand command = command(exporter, recovery);

        int exit = new CommandLine(command).execute("--profile", "reputation");

        assertThat(exit).isZero();
        assertThat(calls).containsExactly("recover", "export:reputation");
    }

    @Test
    void helpDoesNotResolveOrInvokeExportGraph() {
        AtomicInteger calls = new AtomicInteger();
        ExportArtifactsUseCase exporter = command -> {
            calls.incrementAndGet();
            return ExportArtifactsResult.unchanged(command.profile());
        };
        RecoverExportUseCase recovery = () -> calls.incrementAndGet();
        ExportCommand command = command(exporter, recovery);

        int exit = new CommandLine(command).execute("--help");

        assertThat(exit).isZero();
        assertThat(calls).hasValue(0);
    }

    private ExportCommand command(ExportArtifactsUseCase exporter, RecoverExportUseCase recovery) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("exporter", exporter);
        beans.addBean("recovery", recovery);
        return new ExportCommand(
                beans.getBeanProvider(ExportArtifactsUseCase.class),
                beans.getBeanProvider(RecoverExportUseCase.class),
                "test");
    }
}
