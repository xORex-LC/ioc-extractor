# Standalone library consumer

JDK 21 consumer of `io.github.xorex-lc:ioc-platform-concurrency`. The publication
tool copies this project outside the checkout and supplies a fresh Maven local
repository and an explicit artifact repository. It checks public synchronous
and asynchronous contracts, not the future feeds-collector business policy.

Run through `make library-consumer LIBRARY_BUNDLE=... LIBRARY_REPOSITORY=...`; see
[publication guide](../../../docs/guides/library-publication.md).
