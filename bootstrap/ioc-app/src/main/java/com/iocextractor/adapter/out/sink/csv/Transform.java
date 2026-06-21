package com.iocextractor.adapter.out.sink.csv;

/**
 * Transforms a cell value ({@code transform: name} or {@code name:arg}).
 * Thin and reusable; new transform = new class registered by key.
 */
public interface Transform {

    String apply(String value, String arg);
}
