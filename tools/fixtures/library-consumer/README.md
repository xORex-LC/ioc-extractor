# Standalone library consumer

JDK 21 consumer of `io.github.xorex-lc:ioc-platform-concurrency`. The publication
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
