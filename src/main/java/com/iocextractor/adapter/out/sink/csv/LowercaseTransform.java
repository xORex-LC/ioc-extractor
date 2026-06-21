package com.iocextractor.adapter.out.sink.csv;

import java.util.Locale;

/** Transform {@code lower}: lower-cases the value. */
public final class LowercaseTransform implements Transform {

    @Override
    public String apply(String value, String arg) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
