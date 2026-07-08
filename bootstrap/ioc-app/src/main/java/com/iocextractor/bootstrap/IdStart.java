package com.iocextractor.bootstrap;

/**
 * Configured public artifact id start value.
 *
 * <p>{@code auto} continues from durable artifact state; {@code explicit(long)}
 * uses the configured starting id as-is.</p>
 */
public sealed interface IdStart permits IdStart.Auto, IdStart.Explicit {

    /** Canonical text used for diagnostics and deterministic fingerprints. */
    String normalized();

    /** Returns the automatic baseline mode. */
    static IdStart auto() {
        return Auto.INSTANCE;
    }

    /** Returns an explicit starting id. */
    static IdStart explicit(long value) {
        return new Explicit(value);
    }

    /** Parses {@code auto} or a signed 64-bit integer. */
    static IdStart parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalid(value);
        }
        String candidate = value.trim();
        if ("auto".equalsIgnoreCase(candidate)) {
            return auto();
        }
        try {
            return explicit(Long.parseLong(candidate));
        } catch (NumberFormatException ex) {
            throw invalid(value);
        }
    }

    /** Converts a typed YAML numeric scalar through the same signed 64-bit contract. */
    static IdStart from(Number value) {
        if (value == null) {
            throw invalid(null);
        }
        try {
            return explicit(Long.parseLong(value.toString()));
        } catch (NumberFormatException ex) {
            throw invalid(value);
        }
    }

    private static IllegalArgumentException invalid(Object value) {
        return new IllegalArgumentException("Invalid ioc.sink.artifacts[].id.start value '" + value
                + "'; use 'auto' or an integer within signed 64-bit range");
    }

    /** Automatic start resolved from {@link com.iocextractor.application.port.out.artifact.ArtifactIdBaseline}. */
    final class Auto implements IdStart {

        private static final Auto INSTANCE = new Auto();

        private Auto() {
        }

        @Override
        public String normalized() {
            return "auto";
        }

        @Override
        public String toString() {
            return normalized();
        }
    }

    /** Explicit configured starting id. */
    record Explicit(long value) implements IdStart {

        @Override
        public String normalized() {
            return Long.toString(value);
        }
    }
}
