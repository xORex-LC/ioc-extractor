# PSL host-classifier tests

Local behavioral checks for the [production classifier](../../../../../../../main/java/com/iocextractor/adapter/out/psl/README.md).

| Suite | Observable contracts |
|---|---|
| [PslHostClassifierTest.java](PslHostClassifierTest.java) | Registrable/subdomain/IP classification, PRIVATE hosting suffixes, a non-listed provider, onion classification and empty input |

The suite is Surefire-discovered. Its nine parameterized host cases plus two
ordinary tests use fixed inline strings and the actual packaged Guava data.
There is no Spring context, DNS query, external service or resource fixture tree.
Expected results are explicit `HostKind` values, not computed by a second call
to the same classifier.

These checks protect representative suffix boundaries; they do not prove
complete PSL coverage, current provider registration/reachability, IP-octet or
Tor-address validation. There are no dedicated local assertions for null,
uppercase/whitespace/root-dot forms, malformed names, IPv6 or concurrent load.
The production README describes the implementation's broader input behavior;
do not infer complete validation from the positive IP/onion fixtures.

Guava upgrades can change the bundled PRIVATE/public suffix boundaries. Review
changed fixture results against intended classification rather than blindly
refreshing expectations. Domain feature-extractor and match-policy tests own
IOC parsing and the effect of `HostKind` on rule decisions; this module does
not assert configured mask codes.

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-psl
```

There are no local Failsafe suites. Full reactor acceptance uses `make verify`.
[TESTING.md](../../../../../../../../../../docs/TESTING.md) owns suite discovery and assertion policy;
[processing internals](../../../../../../../../../../docs/dev/processing.md) explains composition.
