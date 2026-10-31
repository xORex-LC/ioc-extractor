# ioc-platform-diagnostics-api

JDK 21 diagnostic values, consumer-owned codes, failure decisions, bounded
collectors and synchronous sinks. Coordinates:
`io.github.xorex-lc:ioc-platform-diagnostics-api:${revision}`. The version follows
the IOC reactor. Release `0.3.0-rc.1` is available from Maven Central and GitHub
Packages; both repositories passed separate cold-consumer checks in
[publication run 39137777538](https://github.com/xORex-LC/ioc-extractor/actions/runs/39137777538).

## Purpose and dependencies

The module has **no production dependencies** outside the JDK. It must not
import IOC catalogs, product exceptions, Spring, SLF4J or other platform modules.

## Structure

| Package | Responsibility |
|---|---|
| [`diagnostics`](src/main/java/com/iocextractor/platform/diagnostics/README.md) | Immutable occurrences, code contract, clock-based builders |
| [`result`](src/main/java/com/iocextractor/platform/diagnostics/result/README.md) | Values, pure policies, exact counts and bounded samples |
| [`sink`](src/main/java/com/iocextractor/platform/diagnostics/sink/README.md) | Synchronous delivery, collecting/no-op/resilient sinks |

## Usage and verification

Start with the [English consumer guide](../../docs/guides/libraries/diagnostics.md)
or [Russian consumer guide](../../docs/guides/ru/libraries/diagnostics.md).
The accepted boundary and compatibility policy are recorded in
[ADR 0044](../../docs/ADR/0044-reusable-diagnostics-contract.md).
`package-info.java` and public Javadoc describe the API contracts. The
[behavior examples](src/test/java/com/iocextractor/platform/diagnostics/DiagnosticsLibraryExampleTest.java)
use their own code enum and clock, without the IOC catalog. The
[standalone consumer](../../tools/fixtures/diagnostics-consumer/README.md)
extracts and executes the three actual guide sources; Java/XML blocks must match
the Russian version. The
[permanent release record](../../.github/release-notes/ioc-platform-diagnostics-api-0.3.0-rc.1.md)
retains immutable source, signing, hashes and repository evidence.

Run `make test-module MODULE=platform/platform-diagnostics-api` from the repository
root. IOC integration remains in `platform-diagnostics`, `platform-etl` and
`platform-diagnostics-logging`. Build a standalone flattened POM, main,
sources and strict Javadoc with `make library-bundle LIBRARY_COMPONENT=diagnostics`
using a new `LIBRARY_BUNDLE` directory. `make library-consumer` with the same
selector and directory runs the guide examples outside the checkout with a fresh
cache. See the [publication procedure](../../docs/guides/library-publication.md).
This module supplies no Spring starter.
