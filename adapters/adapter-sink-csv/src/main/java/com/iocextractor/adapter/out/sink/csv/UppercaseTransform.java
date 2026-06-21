package com.iocextractor.adapter.out.sink.csv;

import java.util.Locale;

/** Transform {@code upper}: upper-cases the value. */
public final class UppercaseTransform implements Transform {

    @Override
    public String apply(String value, String arg) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
