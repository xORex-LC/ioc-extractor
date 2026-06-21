package com.iocextractor.observability;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingTaxonomyTest {

    @Test
    void event_actions_are_stable_and_unique() {
        assertThat(Arrays.stream(EventAction.values()).map(EventAction::value).toList())
                .containsExactly(
                        "app_start",
                        "app_stop",
                        "command_start",
                        "command_complete",
                        "stage_start",
                        "stage_complete",
                        "lookup_load",
                        "source_read",
                        "artifact_write",
                        "diagnostic_emit")
                .doesNotHaveDuplicates();
    }

    @Test
    void log_fields_are_stable_and_unique() {
        assertThat(Arrays.stream(LogField.values()).map(LogField::key).toList())
                .containsExactly(
                        "event.action",
                        "event.outcome",
                        "event.duration",
                        "file.path",
                        "ioc.run.id",
                        "ioc.source.id",
                        "ioc.mode",
                        "ioc.stage",
                        "ioc.source.path",
                        "ioc.source.content_hash",
                        "ioc.artifact.name",
                        "ioc.rows",
                        "ioc.diagnostic.code",
                        "ioc.diagnostic.category",
                        "ioc.diagnostic.severity")
                .doesNotHaveDuplicates();
    }
}
