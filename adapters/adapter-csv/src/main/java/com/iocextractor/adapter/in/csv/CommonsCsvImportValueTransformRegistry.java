package com.iocextractor.adapter.in.csv;

import com.iocextractor.adapter.out.sink.csv.MappingValueException;
import com.iocextractor.adapter.out.sink.csv.Transform;
import com.iocextractor.application.dataframeimport.mapping.ImportValueMappingException;
import com.iocextractor.application.port.out.dataframeimport.ImportValueTransformRegistry;

import java.util.Map;
import java.util.Objects;

/** Reuses the validated CSV transform family behind the import application port. */
public final class CommonsCsvImportValueTransformRegistry implements ImportValueTransformRegistry {

    private final Map<String, Transform> transforms;

    /** Creates an immutable registry over the composition-root transform set. */
    public CommonsCsvImportValueTransformRegistry(Map<String, Transform> transforms) {
        this.transforms = Map.copyOf(Objects.requireNonNull(transforms, "transforms"));
    }

    @Override
    public String transform(String specification, String value) {
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(value, "value");
        int separator = specification.indexOf(':');
        String name = separator < 0 ? specification : specification.substring(0, separator);
        String argument = separator < 0 ? null : specification.substring(separator + 1);
        Transform transform = transforms.get(name);
        if (transform == null) {
            throw new IllegalStateException("Compiled import contract references an unknown transform");
        }
        try {
            return transform.apply(value, argument);
        } catch (MappingValueException failure) {
            throw new ImportValueMappingException("Import value transform rejected the current cell", failure);
        }
    }
}
