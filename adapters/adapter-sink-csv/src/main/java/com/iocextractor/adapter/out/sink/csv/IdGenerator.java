package com.iocextractor.adapter.out.sink.csv;

/**
 * Per-artifact sequential id generator. Artifacts have independent id spaces
 * (e.g. masks count down, hashes count up), so each sink owns one instance.
 */
public final class IdGenerator {

    public enum Strategy {
        ASCENDING, DESCENDING
    }

    private final Strategy strategy;
    private long current;

    public IdGenerator(Strategy strategy, long start) {
        this.strategy = strategy;
        this.current = start;
    }

    public long next() {
        long value = current;
        current += (strategy == Strategy.ASCENDING ? 1 : -1);
        return value;
    }
}
