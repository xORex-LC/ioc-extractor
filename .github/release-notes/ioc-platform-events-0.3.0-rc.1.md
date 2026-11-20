# ioc-platform-events 0.3.0-rc.1

[Русская версия](ru/ioc-platform-events-0.3.0-rc.1.md) ·
[Consumer guide](../../docs/guides/libraries/events.md) ·
[Paired component](ioc-events-spring-0.3.0-rc.1.md)

Published to Central and GitHub Packages on 2026-10-25. Separate cold consumers
passed for each repository. This is a Maven library release, not an IOC service
release or deployment. PR [19](https://github.com/xORex-LC/ioc-extractor/pull/19)
integrated E1–E5; both component tags identify the same reviewed source/tree.

## Immutable identity

| Field | Value |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-platform-events:0.3.0-rc.1` |
| Source | `46c914418e721322903a127584fd5d99dff9f52b` |
| Source tree | `0cd880399fd5dce1db6bdc92f40ebc5762c300c9` |
| Annotated tag | `ioc-platform-events-v0.3.0-rc.1` |
| Tag object | `2e5e604a261bf41bd4d574b595c94d10647d05f3` |
| Release branch | `release-0.3.0` |
| Validation | [40159541648](https://github.com/xORex-LC/ioc-extractor/actions/runs/40159541648) |
| Publication | [40160627532](https://github.com/xORex-LC/ioc-extractor/actions/runs/40160627532) |
| Central deployment | `61ceaa74-dc39-451e-a989-21c8c2181f99` |
| Signing fingerprint | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| Signed `identity.json` SHA-256 | `1445412b74d2d02533ce679970b977e16a64393f8ae64169adcf228a5b5ff575` |

The owner approved validation/publication reviews for both components. Each
validation signed its clean bundle once. Publication skipped build/signing and
reused the original signed files and deployment. Each bundle has 20 signed/
checksummed files; neither existing coordinate/tag may be rebuilt, resigned,
overwritten or moved for recovery.

| Primary file | SHA-256 |
|---|---|
| POM | `08df0b9ba848f7e7b92383f3492e17ad70c25dd1791423f5c4eaf150c1ede578` |
| Main JAR | `4ee663ce8773ac90f195ab863ac4e5675f2349f57f6bc8f02fdda78d12db7d4e` |
| Sources JAR | `68c89bbff8533200387629bce120b67d548ccbd95f8b6ee54248f03a028d8360` |
| Javadoc JAR | `b2824ffc918992e3c70a1a72636fb2acc1fca9ff584670336cf438119fd3e374` |

## Packaged consumers and extraction outcomes

The JDK consumer compiled/executed the original `LocalEventsExample` from the
actual guide and independently checked metadata validation, no-ops, duplicate
references and shallow snapshots. It rejected Spring, SLF4J and IOC runtime
classes. Four primary files matched the signed manifest and selected Maven
origin. Empty selected repositories with populated alternatives failed closed;
unfiltered positive controls proved the expected bytes were available.

| Stage | Durable outcome |
|---|---|
| E0 | Reconciled real producers, payloads and delivery limits; kept local hints separate from durable recovery and inter-service messaging |
| E1 | Seven JDK-only types with explicit metadata/null/concurrency contracts, public Javadoc, 43 focused cases and an executed unrelated consumer example |
| E2 | Spring delivery moved to a separate artifact; concrete events, listeners, dispatch isolation and recovery stayed service-owned |
| E3 | Parent-independent POM and strict dependency-free archive/embedded-POM checks |
| E4 | Isolated JDK consumer, complete graph, exact guide, four-file provenance and missing-target controls |
| E5 | Core-first protected publication and exact same-version core handoff to the Spring adapter |

The maintained guide and package Javadoc own current behavior. This library
provides no subscriber SPI, queue, wire format, acknowledgement, durable ledger,
keyed executor, automatic IDs or context propagation. The recorder is an
unbounded copy-on-write test fixture retaining event references, not a journal.
The separate adapter's [release record](ioc-events-spring-0.3.0-rc.1.md)
retains historical Spring failure corrections and pair qualification.

## Archives, release gates and limits

Original files, workflow inputs/logs and reviewed qualification receipts remain
under `.dev/events-release-0.3.0-rc.1/`. `core-release.tar.gz` has SHA-256
`f8cc5924affb8f64cf74afaffd38b7dbfaa78c7eb79675ed96f06e91517d9bd2`. Actions retention expires on 2027-01-23;
local copies retain the immutable bytes and logs beyond it.

Release CI `40157656528` passed on exact source `46c91441`, including five
required gates and both packaged consumers. Each protected RC validation passed
full `verify`, 215 publication-tool contracts, its selected consumer and PMD.
Retained source reports contain 137 accepted raw SpotBugs/0 visible/errors/missing,
CPD 22 and PMD 19 advisory/0 blocking. Release CI coverage was 27133/29440 lines
and 10296/12009 branches across 25 production groups. These are historical RC
measurements, not later documentation-gate results; no floor/suppression or
analyzer/dependency scope was relaxed for publication.

The historical library-runtime advisory review returned no matches for the
12 package/version pairs across default/BOM-managed graphs, with vulnerable/fixed
controls and original responses in `library-runtime-advisories.json`.
Core has no third-party runtime dependencies. This was not a whole-service NVD
clearance: run `40157773054` was cancelled during NVD update before its scan ran.
Unrelated service jsoup/Jackson findings remained separate work. No current
vulnerability status is inferred from those historical queries.

The supported baseline is Java 21, Context 7.0.9 and SLF4J API 2.0.18 for the
adapter, with plain and Boot 4.0.8 BOM-managed graphs. Product/library versions
remain lockstep. A second deployed service, another framework baseline,
asynchronous all-handler completion and an external broker remain unqualified.
ETL stays internal. Configuration's original Central CI failure and protected
recovery remain separate TOOL-8 evidence; no events result closes that follow-up.
