package com.iocextractor.observability;

import com.iocextractor.observability.logging.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingTaxonomyTest {

    private static final Set<String> ACTION_AREAS = Set.of(
            "app", "cli", "pipeline", "source", "sink", "maintenance", "storage",
            "export", "sync", "events", "diagnostics", "lifecycle", "import");

    private static final Set<String> ECS_FIELD_PREFIXES = Set.of("event.", "error.", "file.");

    @Test
    void event_actions_are_stable_and_unique() {
        assertThat(EventAction.values()).extracting(EventAction::value).doesNotHaveDuplicates();
        assertThat(EventAction.values()).allSatisfy(action -> {
            assertThat(action.value()).matches("[a-z]+(?:_[a-z]+)*");
            assertThat(action.area()).isIn(ACTION_AREAS);
            assertThat(action.description()).isNotBlank();
        });
    }

    @Test
    void log_fields_are_stable_and_unique() {
        assertThat(LogField.values()).extracting(LogField::key).doesNotHaveDuplicates();
        assertThat(LogField.values()).allSatisfy(field -> {
            assertThat(field.key().startsWith("ioc.")
                    || ECS_FIELD_PREFIXES.stream().anyMatch(field.key()::startsWith)).isTrue();
            assertThat(field.valueType()).isNotNull();
            assertThat(field.description()).isNotBlank();
        });
    }

    @Test
    void structured_log_api_does_not_accept_arbitrary_keys() {
        assertThat(Arrays.stream(LogEvent.class.getMethods())
                .filter(method -> method.getName().equals("field"))
                .map(method -> method.getParameterTypes()[0].getName()))
                .containsExactly(LogField.class.getName());
    }
}
