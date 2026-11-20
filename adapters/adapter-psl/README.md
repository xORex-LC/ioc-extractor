# adapters/adapter-psl

## Purpose and boundary

Guava-backed implementation of the domain `HostClassifier` port. It maps a bare
host to `HostKind`, including the registrable-domain/subdomain distinction needed
by mask classification. The internal reactor artifact is
`com.iocextractor:ioc-adapter-psl`; it is not a runnable application or separately
published library.

This adapter owns Public Suffix List lookup and local IP/onion classification
shortcuts. Address parsing, IOC normalization, configured match rules and output
codes remain with their domain/application owners. A returned kind is a
classification result, not proof of a valid, registered or reachable address.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Domain-port and Guava dependencies |
| [Production package](src/main/java/com/iocextractor/adapter/out/psl/README.md) | Classifier, precedence, PRIVATE suffix semantics and input limits |
| [Test package](src/test/java/com/iocextractor/adapter/out/psl/README.md) | Fixed host-kind expectations and local evidence boundaries |

There is one production class and one matching test class, with inline host
fixtures and no module-local resource directory.

## Dependencies and suffix data

Production depends directly on `ioc-domain` and Guava. The parent manages versions
and the Java 21 baseline. No Spring, application, bootstrap or sibling-adapter
dependency is required. Inward code uses the domain port; bootstrap may construct
the concrete adapter. Guava types stay outside the domain API.

Classification uses Guava's bundled public-suffix data, including PRIVATE entries.
There is no DNS lookup or suffix-list download/refresh path in this adapter.
Changing the Guava dependency can change host kinds even if this class is
unchanged. Review the fixtures and resulting match decisions during an upgrade;
do not equate a hosting provider name with a private suffix unless the packaged
list actually contains it.

## Entry points and integration

Construct `PslHostClassifier` and call `classify` with a bare host. It is stateless,
owns no closeable resource and returns only `HostKind`. Null/empty input is
UNKNOWN; IPv4-shaped strings and `.onion` names are checked before PSL parsing.
The production README explains why these shortcuts do not validate IP octets,
Tor addresses or arbitrary network input.

[AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
wires the implementation into
[DefaultIndicatorFeatureExtractor](../../core/ioc-domain/src/main/java/com/iocextractor/domain/feature/DefaultIndicatorFeatureExtractor.java).
That owner normalizes/parses the IOC and passes its extracted host to the port.
`RuleBasedMatchPolicy` then evaluates configured predicates/codes. The adapter
does not select a match variant itself or depend on pipeline stage numbering.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-psl
```

The local suite runs in Surefire; there are no module Failsafe suites. Full
reactor acceptance uses `make verify`. See the test README for coverage limits,
[processing internals](../../docs/dev/processing.md) for feature/rule composition
and the [domain port](../../core/ioc-domain/src/main/java/com/iocextractor/domain/feature/HostClassifier.java)
for the inward dependency boundary. Exact operation guarantees remain in Javadoc.
