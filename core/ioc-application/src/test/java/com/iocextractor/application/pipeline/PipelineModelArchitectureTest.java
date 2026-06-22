package com.iocextractor.application.pipeline;

import com.iocextractor.application.pipeline.stage.AttributeSourceStage;
import com.iocextractor.application.pipeline.stage.DeduplicateIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStage;
import com.iocextractor.application.pipeline.stage.RefangStage;
import com.iocextractor.application.pipeline.stage.WriteArtifactsStage;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineModelArchitectureTest {

    private static final List<Class<?>> STAGES = List.of(
            ReadSourceStage.class,
            RefangStage.class,
            ExtractIndicatorsStage.class,
            AttributeSourceStage.class,
            DeduplicateIndicatorsStage.class,
            WriteArtifactsStage.class);

    @Test
    void all_concrete_stages_implement_stage_contract() {
        assertThat(STAGES).allSatisfy(stage -> assertThat(Stage.class).isAssignableFrom(stage));
    }

    @Test
    void concrete_stages_do_not_hold_references_to_other_concrete_stages() {
        assertThat(STAGES).allSatisfy(stage -> {
            for (Field field : stage.getDeclaredFields()) {
                assertThat(STAGES)
                        .as("%s field %s must not reference another concrete stage", stage.getSimpleName(), field.getName())
                        .doesNotContain(field.getType());
            }
        });
    }

    @Test
    void concrete_stages_have_unique_stage_names() {
        var names = STAGES.stream()
                .map(this::stageName)
                .toList();

        assertThat(names).doesNotHaveDuplicates();
    }

    private StageId stageName(Class<?> stageType) {
        try {
            var constructor = stageType.getConstructors()[0];
            var args = java.util.Arrays.stream(constructor.getParameterTypes())
                    .map(this::stub)
                    .toArray();
            return ((Stage<?, ?>) constructor.newInstance(args)).name();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot instantiate " + stageType.getSimpleName(), ex);
        }
    }

    private Object stub(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (List.class.equals(type)) {
            return List.of();
        }
        if (type == java.time.Clock.class) {
            return java.time.Clock.systemUTC();
        }
        if (type.isInterface()) {
            return java.lang.reflect.Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[]{type},
                    (proxy, method, args) -> defaultValue(method.getReturnType()));
        }
        throw new IllegalArgumentException("No stub for " + type);
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == long.class) {
            return 0;
        }
        if (List.class.equals(type)) {
            return List.of();
        }
        return null;
    }
}
