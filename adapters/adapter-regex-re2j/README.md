# adapters/adapter-regex-re2j

## Purpose and boundary

Implementations of the domain `PatternEngine` port: default RE2/J and an explicit
JDK regex alternative. The internal reactor artifact is
`com.iocextractor:ioc-adapter-regex-re2j`; it is not a runnable application or
separately published library.

The module owns compilation and engine-neutral match cursors/spans. Patterns,
IOC-type priority, overlap decisions, source attribution and document resource
budgets remain with callers. It neither validates IOC values nor normalizes,
refangs or classifies matched text.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Domain-port/RE2J dependencies and test support |
| [Production package](src/main/java/com/iocextractor/adapter/out/regex/README.md) | Two public engines and shared internal compiled-pattern/cursor adapters |
| [Test package](src/test/java/com/iocextractor/adapter/out/regex/README.md) | Shared executable contract for both engines |

Four production files share one test package. Fixtures are inline; there is no
module-local resource directory.

## Dependencies

Production depends on `ioc-domain` and RE2J. The JDK engine uses `java.util.regex`
without another library. The parent manages dependencies and the Java 21 baseline.
`ioc-application-tck` is test-scoped for its contract annotation; the regex contract
suite itself lives in this module.

Production does not depend on application, bootstrap, Spring or sibling adapters.
Inward callers use the domain port; bootstrap may construct either concrete
implementation. Engine-specific Pattern/Matcher types stay behind that port.

## Entry points and contracts

Engine IDs are `re2j` and `jdk`. Compile once and retain the returned `Compiled`
for reuse. `matches(CharSequence)` creates a new stateful cursor; offsets are
half-open UTF-16 positions in the entire supplied sequence. `findAll` consumes
such a cursor and materializes a complete mutable list of spans and strings.
The production README explains borrowed-input lifetime and match-value allocation.

[AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
selects one implementation using `ioc.engine`: `re2j` by default, `jdk` when
explicitly configured. A RE2/J compilation/matching error does not automatically
switch the call to JDK. Both detection and section-marker callers use the port.

Project patterns must stay RE2-compatible, including avoiding look-around and
back-references, even when the JDK alternative is selected. The adapters delegate
syntax handling to their engines; they do not translate expressions or enforce
the common subset inside the JDK compiler.

RE2/J avoids catastrophic backtracking; JDK regex retains backtracking risk.
Neither adapter supplies a match-length/count quota, deadline or cancellation
mechanism. Linear matching does not establish whole-process memory bounds, and
unbounded `findAll` still allocates every result. The production document path
uses lazy cursors with caller-owned field limits and workspace admission.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-regex-re2j
```

The local suite runs in Surefire with the `contract` tag; there are no local
Failsafe suites. Full reactor acceptance uses `make verify`, including bootstrap
engine/configured-pattern tests. See the test README for exact evidence limits,
[processing internals](../../docs/dev/processing.md) for bounded extraction
and [configuration](../../docs/guides/configuration.md) for operator settings.
