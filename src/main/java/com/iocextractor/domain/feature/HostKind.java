package com.iocextractor.domain.feature;

/**
 * Kind of a host, used by mask classification to choose the match variant.
 * The registrable-vs-subdomain distinction is decided against the Public Suffix
 * List by a {@link HostClassifier} adapter — the domain only sees the result.
 */
public enum HostKind {

    /** Literal IP address (e.g. {@code 1.2.3.4}). */
    IP,

    /** Registrable domain — the registrable name itself (e.g. {@code example.com}). */
    REGISTRABLE,

    /** Subdomain of a registrable domain (e.g. {@code a.example.com}). */
    SUBDOMAIN,

    /** Tor hidden service address ({@code *.onion}). */
    ONION,

    /** Could not be classified (no public suffix, malformed, …). */
    UNKNOWN
}
