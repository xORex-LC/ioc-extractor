# Spring events consumer

An unrelated collector uses only the adapter's main dependency; its published
POM must supply the core event classes, Spring Context and SLF4J API. Documentation
classifiers exclude those dependencies so they cannot repair a broken main POM.
Both exact Java examples from the [guide](../../../docs/guides/events-library.md)
are extracted at execution time. No Boot runtime, logging provider, IOC bootstrap
or shared test-support dependency is present.

The fixture checks partial synchronous delivery, original Error propagation,
observer failure containment, and service ownership of the multicaster, handler
and executor. All contexts close; the service executor runs inline and creates no
worker. The Maven harness also runs plain Java outside the plugin classloader,
reviews the whole graph, and checks default/application-BOM mediation, missing
and unavailable APIs, and incompatible Spring mediation.

Run through `make library-consumer LIBRARY_COMPONENT=events-spring` with a prepared
`LIBRARY_BUNDLE` and `LIBRARY_DEPENDENCY_BUNDLE` naming the same-version core bundle.
Local consumption assembles both bundles in one temporary selected repository.
All eight files must match their manifests and resolve only from that repository.
Routing probes use fresh caches: the whole selected target absent, then only core
absent. A populated alternate must not repair either filtered probe; unfiltered
controls prove it can supply exactly the missing bytes. Temporary inputs/caches
are removed on exit. Failure is fatal; this is not registry publication evidence.
