package com.iocextractor.platform.logging;

import java.nio.file.Path;
import java.util.UUID;

/** Converts supported inputs without changing a field's declared wire type. */
final class LogValueNormalizer {
    private LogValueNormalizer() { }

    static Object normalize(LogFieldSpec specification, Object input) {
        if (input == null) {
            return null;
        }
        return switch (specification.valueType()) {
            case STRING -> switch (input) {
                case CharSequence text -> text.toString();
                case Enum<?> enumeration -> enumeration.toString();
                case Path path -> path.toString();
                case UUID identifier -> identifier.toString();
                default -> throw mismatch(specification, input);
            };
            case LONG -> switch (input) {
                case Byte number -> number.longValue();
                case Short number -> number.longValue();
                case Integer number -> number.longValue();
                case Long number -> number;
                default -> throw mismatch(specification, input);
            };
            case BOOLEAN -> {
                if (input instanceof Boolean) {
                    yield input;
                }
                throw mismatch(specification, input);
            }
        };
    }

    private static IllegalArgumentException mismatch(LogFieldSpec specification, Object input) {
        return new IllegalArgumentException("Field " + specification.key() + " requires "
                + specification.valueType() + "; received " + input.getClass().getName());
    }
}
