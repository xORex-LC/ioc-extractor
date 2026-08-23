package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/**
 * One mapped import cell that preserves the distinction between no instruction,
 * an explicit clear and a concrete value until merge is complete.
 *
 * @param presence cell presence state
 * @param value non-null only for {@link Presence#VALUE}
 */
public record ImportCell(Presence presence, String value) {

    /** Tri-state cell presence. */
    public enum Presence {
        /** Source column or mapping is absent. */
        ABSENT,
        /** Source explicitly supplies an empty/null value. */
        NULL,
        /** Source supplies a concrete string, including whitespace when policy preserves it. */
        VALUE
    }

    /** Enforces the one valid representation for each presence state. */
    public ImportCell {
        Objects.requireNonNull(presence, "presence");
        if (presence == Presence.VALUE && value == null) {
            throw new IllegalArgumentException("VALUE import cell requires a non-null value");
        }
        if (presence != Presence.VALUE && value != null) {
            throw new IllegalArgumentException(presence + " import cell must not carry a value");
        }
    }

    /**
     * Returns a cell that gives no update instruction.
     *
     * @return absent cell
     */
    public static ImportCell absent() {
        return new ImportCell(Presence.ABSENT, null);
    }

    /**
     * Returns a cell that explicitly represents null.
     *
     * @return null cell
     */
    public static ImportCell nullValue() {
        return new ImportCell(Presence.NULL, null);
    }

    /**
     * Returns a cell carrying exact mapped text.
     *
     * @param value non-null mapped text
     * @return value cell
     */
    public static ImportCell value(String value) {
        return new ImportCell(Presence.VALUE, Objects.requireNonNull(value, "value"));
    }
}
