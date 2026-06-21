package com.iocextractor.domain.feature;

/**
 * Domain port: classify a host into a {@link HostKind}. The registrable-vs-subdomain
 * decision needs the Public Suffix List, so the implementation lives in an adapter
 * (Guava) — the domain depends only on this interface, never on a PSL library.
 */
public interface HostClassifier {

    HostKind classify(String host);
}
