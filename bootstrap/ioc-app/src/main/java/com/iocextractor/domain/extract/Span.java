package com.iocextractor.domain.extract;

/**
 * A half-open match range {@code [start, end)} and its matched text.
 * Engine-neutral so the domain never depends on a concrete regex library.
 */
public record Span(int start, int end, String value) {
}
