# com.iocextractor.adapter.out.psl

Implementation of the domain `HostClassifier` port using Guava
`InternetDomainName`. Follow the [module dependency rules](../../../../../../../../README.md).

| File | Responsibility |
|---|---|
| [PslHostClassifier.java](PslHostClassifier.java) | Ordered local shortcuts and PSL-backed host-kind lookup |

## Classification order and limits

`classify` applies these checks in order:

1. Null or empty input returns UNKNOWN.
2. Four dot-separated groups of one to three ASCII digits return IP. This is a
   shape check: it does not enforce the 0–255 octet range or reject leading zeros.
3. Other input is lowercased with `Locale.ROOT`. A suffix of `.onion` returns
   ONION without PSL lookup or Tor version/length/checksum validation.
4. Guava's top-private-domain check returns REGISTRABLE; a name below its
   registrable boundary returns SUBDOMAIN. Other parseable names return UNKNOWN.
   Guava `IllegalArgumentException` / `IllegalStateException` also return UNKNOWN.

Input is a host, not a complete URL, authority with port or IOC document token.
The adapter does not trim whitespace, refang input or add an IPv6 classification
path. Guava's own domain-name handling still applies, including an accepted final
root dot. It returns a kind without returning a normalized host value. UNKNOWN
combines malformed and unrecognized-suffix outcomes; there is no diagnostic
explaining which occurred.

## Public and PRIVATE suffix boundaries

Guava's public-suffix lookup includes PRIVATE entries, not just registry suffixes.
A listed suffix itself is not a registrable host. Synthetic examples under the
current packaged suffix data illustrate the boundary:

| Host | Kind |
|---|---|
| `example.com` / `a.example.com` | REGISTRABLE / SUBDOMAIN |
| `workers.dev` | UNKNOWN: the private suffix itself |
| `tenant.workers.dev` / `a.tenant.workers.dev` | REGISTRABLE / SUBDOMAIN |
| `tenant.duckdns.org` | REGISTRABLE |
| `999.999.999.999` | IP: lexical shortcut, not a valid IPv4 address |
| `tenant.onion` | ONION: suffix shortcut, not a validated Tor address |

A provider absent from PRIVATE data follows the ordinary suffix boundary.
Classification reflects the Guava dependency snapshot rather than a live DNS
or registry query. Do not replace it with label counting or a hard-coded provider
list; dependency upgrades require reviewing changed classifications.

## Ownership and callers

The instance retains no per-host state or cache. The shared compiled IPv4 pattern
creates a matcher per call; all other working values are local. No resource needs
closing. Basic classification is suitable for instance reuse, but local tests
do not qualify throughput or concurrent load.

[DefaultIndicatorFeatureExtractor](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/feature/DefaultIndicatorFeatureExtractor.java)
owns IOC normalization/address parsing before invoking the port.
[RuleBasedMatchPolicy](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/classify/RuleBasedMatchPolicy.java)
uses the resulting features with configured first-match rules. PSL kind alone
does not decide path/port/query treatment or emitted match codes. Guava remains
behind the domain port; the caller sees only
[HostKind](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/feature/HostKind.java).

The [test package](../../../../../../../test/java/com/iocextractor/adapter/out/psl/README.md) maps current regression coverage;
[processing internals](../../../../../../../../../../docs/dev/processing.md) owns the cross-module flow.
