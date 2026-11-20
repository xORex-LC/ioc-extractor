# Standalone library consumer

Java 21 consumer of `io.github.xorex-lc:ioc-platform-concurrency`. The publication
tool copies this project outside the checkout and supplies a fresh Maven local
repository and an explicit artifact repository. It checks public synchronous
and asynchronous contracts. It also compiles and runs both exact Java examples
from the [consumer guide](../../../docs/guides/libraries/concurrency.md), checking
that Java and Maven dependency blocks match the Russian translation. The
publication tool supplies these sources; running the fixture directly without
that preparation is unsupported. Future feeds-collector business policy remains
service-owned.

Run through `make library-consumer LIBRARY_BUNDLE=... LIBRARY_REPOSITORY=...`; see
[publication guide](../../../docs/guides/library-publication.md).

## Source map and ownership

| Source | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Standalone Java 21 compiler, packaged dependencies/repositories and executable entry point |
| [LibraryConsumer.java](LibraryConsumer.java) | Independent consumer assertions and calls into prepared guide examples |

The harness supplies `KeyedGuardExample`, `KeyedExecutorExample`. Generated guide sources live only in the
temporary copy; the tracked consumer remains an independent contract witness.

The independent source checks same-key reentrancy and exception identity for the
synchronous guard, then bounded per-key admission, another-key progress, accepted
work ordering and shutdown. Latches use bounded waits; `finally` releases blocked
work, shuts down the executor and verifies worker termination. This is a two-worker
packaged-API witness, not a scheduler stress test or durable queue guarantee.

The [fixture overview](../README.md) owns preparation, caches, routing, cleanup
and common evidence limits. The [tool test reference](../../tests/README.md)
distinguishes offline harness contracts from actual packaged consumption.
