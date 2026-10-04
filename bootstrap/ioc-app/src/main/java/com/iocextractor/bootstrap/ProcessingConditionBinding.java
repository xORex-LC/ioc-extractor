package com.iocextractor.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Restarts typed binding at a singular recursive NOT child, using the already resolved values. */
final class ProcessingConditionBinding {
    private ProcessingConditionBinding() {
    }

    static IocProcessingProperties.Condition bind(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        flatten("", source, properties);
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("", Bindable.of(IocProcessingProperties.Condition.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElse(null);
    }

    private static void flatten(String path, Object value, Map<String, Object> target) {
        if (value instanceof Map<?, ?> values) {
            values.forEach((key, child) -> {
                String segment = key.toString();
                String childPath = path.isEmpty() || segment.startsWith("[")
                        ? path + segment : path + "." + segment;
                flatten(childPath, child, target);
            });
        } else if (value instanceof Iterable<?> values) {
            int index = 0;
            for (Object child : values) {
                flatten(path + "[" + index++ + "]", child, target);
            }
        } else {
            target.put(path, value);
        }
    }
}
