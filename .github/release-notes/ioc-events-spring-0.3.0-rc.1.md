# ioc-events-spring 0.3.0-rc.1

[Русская версия](ru/ioc-events-spring-0.3.0-rc.1.md) ·
[Consumer guide](../../docs/guides/libraries/events-spring.md) ·
[Paired component](ioc-platform-events-0.3.0-rc.1.md)

Published to Central and GitHub Packages on 2026-10-25. Separate cold consumers
passed for each repository. This is a Maven library release, not an IOC service
release or deployment. PR [19](https://github.com/xORex-LC/ioc-extractor/pull/19)
integrated E1–E5; both component tags identify the same reviewed source/tree.

## Immutable identity

| Field | Value |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-events-spring:0.3.0-rc.1` |
| Source | `46c914418e721322903a127584fd5d99dff9f52b` |
| Source tree | `0cd880399fd5dce1db6bdc92f40ebc5762c300c9` |
| Annotated tag | `ioc-events-spring-v0.3.0-rc.1` |
| Tag object | `554343b6b72602c742c407e2e09fa7a47c72073c` |
| Release branch | `release-0.3.0` |
| Validation | [40161887317](https://github.com/xORex-LC/ioc-extractor/actions/runs/40161887317) |
| Publication | [40162675262](https://github.com/xORex-LC/ioc-extractor/actions/runs/40162675262) |
| Central deployment | `aceef9e3-888d-44aa-9bc6-45a1f57130ad` |
| Signing fingerprint | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| Signed `identity.json` SHA-256 | `e358c7006a87ac98bcbd81b3bde1334c45a61e6bb75bc1969efe5dcf96caa800` |

The owner approved validation/publication reviews for both components. Each
validation signed its clean bundle once. Publication skipped build/signing and
reused the original signed files and deployment. Each bundle has 20 signed/
checksummed files; neither existing coordinate/tag may be rebuilt, resigned,
overwritten or moved for recovery.

| Primary file | SHA-256 |
|---|---|
| POM | `183eb1c85bb20c8858665357e05e72664e65af7bb2820a36b9193252dea1776a` |
| Main JAR | `2ba1695c8ea007ef793339ca30fbba1b0781192ceff7f2f962f5fd0ac3177bbd` |
| Sources JAR | `58ff3ca379b6969e8c7ea6cdee7e797b0e72e1650741d62ce3c54d7809d69c50` |
| Javadoc JAR | `cc01ecaf05db5545d8bb618eb251ac173ed4b4de6b40a0439b5b9410f320ded9` |

## Packaged consumers and retained evidence

Adapter validation admitted successful core publication `40160627532`. Adapter
publication recovered the original `events-core` and qualification rather than
accepting a replacement core-run input. The retained core manifest is
`1445412b74d2d02533ce679970b977e16a64393f8ae64169adcf228a5b5ff575`.

Separate Central/GitHub consumers compiled both original guide examples and ran
real-context behavior checks under default and Boot 4.0.8 BOM-managed graphs.
They checked eight primary files against both manifests and selected origins;
no Boot runtime or logging provider was supplied. Excluded/unavailable core,
Context and SLF4J, incompatible Spring, missing adapter and missing core were
negative cases. Populated alternate repositories could not repair the filtered
missing-target/core probes; both unfiltered eight-file controls passed.

Actual logs and qualification receipts were inspected, not only green Actions
conclusions. Receipts bind source/version, signed manifest and consumer-log hashes;
the adapter receipt also binds original core. A local review helper initially
expected two success markers, whereas the fixture executes four times across
the two dependency graphs and plugin/direct-JVM paths. The audit assertion was
corrected and actual logs/hashes were checked; no workflow/artifact rerun or
replacement was used to repair that manual review.

E4 also retained a one-off temporary GPG-key fixture failure. Its cause remains
unresolved as TOOL-7 in [KNOWN-ISSUES](../../docs/KNOWN-ISSUES.md); diagnostic stderr
preservation improved and no automatic retry was added. A later green contract
run does not close that separate debt.

## Historical corrections and ownership

| Stage / finding | Final disposition |
|---|---|
| E0 / ARCH-4 | Reproduced annotated-listener Error wrapped by Spring; E2 propagates the immediate underlying Error with its original identity |
| E0 / OBS-7 | Reproduced loss of primary publication evidence when the observer failed; E2 keeps publication failure primary and adds a distinct observer failure as suppressed when enabled |
| E0 / OBS-8 | Reproduced escaping/reclassified fallback logging failure; E2 separates success observation from delivery and contains the last logger's runtime failures without recursive logging |
| E2 | Explicit adapter and accepted [ADR 0047](../../docs/ADR/0047-explicit-spring-control-event-adapter.md); no beans/executors/error handlers installed |
| E3 | Standalone POM requiring exact-version core, Context 7.0.9 and SLF4J API 2.0.18 |
| E4 | Isolated pair consumers, independent assertions, both graphs, dependency/routing negatives and exact eight-file provenance |
| E5 | Successful qualified core is a protected adapter-release prerequisite; immutable original pair/deployment is reused for partial recovery |

ARCH-4/OBS-7/OBS-8 are corrected historical findings. Default synchronous
multicast can still stop after partial delivery. Service error handlers and
asynchronous hand-off can hide failures from the outer call. Direct Errors from
observation/logging propagate; no blanket nonthrowing contract or retry exists.
Concrete payloads, listeners, safe exception text, thread safety of delegates,
shutdown and durable recovery remain service-owned.

## Archives, release gates and limits

Original files, workflow inputs/logs and reviewed qualification receipts remain
under `.dev/events-release-0.3.0-rc.1/`. `spring-release.tar.gz` has SHA-256
`eb7d65a7ec4cf94f4e21bf8ea00ff92f9194e9d6cfce66603e6cfa382139276c`. Actions retention expires on 2027-01-23;
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
