package com.iocextractor.bootstrap;

/** Closed configuration selector with its canonical external token. */
public interface ConfigSelector {

    /** Returns the canonical configuration token. */
    String token();
}
