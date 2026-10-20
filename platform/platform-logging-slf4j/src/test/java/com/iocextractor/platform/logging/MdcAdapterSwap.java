package com.iocextractor.platform.logging;

import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.lang.reflect.Field;

/** Test-only provider fault injection; SLF4J 2.0.18 has no public adapter setter. */
final class MdcAdapterSwap implements AutoCloseable {
    private final Field adapterField;
    private final MDCAdapter previous;

    MdcAdapterSwap(MDCAdapter replacement) throws ReflectiveOperationException {
        previous = MDC.getMDCAdapter();
        adapterField = MDC.class.getDeclaredField("MDC_ADAPTER");
        adapterField.setAccessible(true);
        adapterField.set(null, replacement);
    }

    @Override
    public void close() throws IllegalAccessException {
        adapterField.set(null, previous);
    }
}
