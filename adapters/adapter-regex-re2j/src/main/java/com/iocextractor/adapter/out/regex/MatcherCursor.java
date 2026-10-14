package com.iocextractor.adapter.out.regex;

import com.iocextractor.domain.extract.MatchCursor;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Adapts either engine's stateful matcher without materializing its input or results. */
record MatcherCursor(BooleanSupplier advance, IntSupplier begin, IntSupplier finish,
                     Supplier<String> capture) implements MatchCursor {
    public boolean next() { return advance.getAsBoolean(); }
    public int start() { return begin.getAsInt(); }
    public int end() { return finish.getAsInt(); }
    public String value() { return capture.get(); }
}
