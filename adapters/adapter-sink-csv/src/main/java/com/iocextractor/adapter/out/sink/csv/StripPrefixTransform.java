package com.iocextractor.adapter.out.sink.csv;

/** Transform {@code strip-prefix:<arg>}: removes the leading prefix {@code arg} if present. */
public final class StripPrefixTransform implements Transform {

    @Override
    public String apply(String value, String arg) {
        if (value != null && arg != null && value.startsWith(arg)) {
            return value.substring(arg.length());
        }
        return value;
    }
}
