# ioc-platform-logging-slf4j 0.3.0-rc.1

[Русская версия](ru/ioc-platform-logging-slf4j-0.3.0-rc.1.md) ·
[Consumer guide](../../docs/guides/libraries/logging-slf4j.md) ·
[Boot ECS recipe](../../docs/guides/libraries/logging-slf4j-boot.md)

Published to Central and GitHub Packages on 2026-10-21. Separate cold plain Java
and Boot consumers passed for each repository. The publication reused the
original signed bundle and Central deployment without rebuilding or signing again.
This qualifies the Java/SLF4J API and documented synchronous Boot recipe, not a
second deployed service or another backend/provider.

## Immutable identity

| Field | Value |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-platform-logging-slf4j:0.3.0-rc.1` |
| Source | `5b9761b7ab0b3d6033e51a82fb735f1a3049ef08` |
| Annotated tag | `ioc-platform-logging-slf4j-v0.3.0-rc.1` |
| Tag object | `0ed9d086e20a23a2f96f6aaf1b7930ed09121bb7` |
| Release branch | `release-0.3.0` |
| Reviewed PR | [17](https://github.com/xORex-LC/ioc-extractor/pull/17), head `716049cf`; merged source has the same tree |
| Merge CI | [39432555971](https://github.com/xORex-LC/ioc-extractor/actions/runs/39432555971) |
| Validation | [39433697620](https://github.com/xORex-LC/ioc-extractor/actions/runs/39433697620) |
| Publication | [39455105300](https://github.com/xORex-LC/ioc-extractor/actions/runs/39455105300) |
| Central deployment | `c03543e6-6ce0-426a-905b-b9e18197f4e0` |
| Signing fingerprint | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| Signed `identity.json` SHA-256 | `36d37dac0a91454c7e306003d433aa8da912040166ebd99b22780b2876020ca0` |

The owner approved both protected-environment reviews. Validation passed the
clean RC reactor, strict Javadoc, 83 publication contracts, isolated plain/Boot
consumers, PMD and signing. Publication skipped the build, resumed the original
validation/deployment and mirrored the same bytes to GitHub Packages. All 20
signed/checksummed files were compared; four additional anonymous Central
downloads matched the primary artifacts.

| Primary file | SHA-256 |
|---|---|
| POM | `8913a98ed2a639df62e62e152b5ae8d4c1d6bc2b04ed8cfd7e7698b5a8621d64` |
| Main JAR | `e11f4b6e14018317f9ce5866c531042a6e7d0ab7fd9c2b1c600d9098536f8a53` |
| Sources JAR | `89de9ff4ad6f525546a6813a765c5b6026bd5f76ce0e31d7382eded324213f6b` |
| Javadoc JAR | `61a90ecb428c4ef1c42b12b91ced58a350d11bffe69283bde1c34cd7c1a9119b` |

## Repository qualification and retained evidence

Each repository consumer used separate temporary projects and empty Maven
caches/settings. The plain fixture executed all three actual guide classes and
rejected missing/unavailable SLF4J API for the expected reasons. Classifier and
provider dependencies could not hide a missing main SLF4J dependency. The Boot
fixture compiled the exact Java/YAML/XML recipe and neutral driver, checked five
JSON records with duplicate-key detection and checked encoder status. No IOC
runtime classes were present. Four primary hashes and repository origin markers
were checked by each fixture; a public dependency fallback could not qualify
GitHub resolution. Actual consumer logs record the successful runs; all 20
release files were byte-identical to validation.

Original files, workflow inputs/logs, source checks and both consumer logs remain
under `.dev/l3-5-publication/`. The recovery archive `signed-release.tar.gz`
has SHA-256 `f3016d5faa8fba0f02e0e48cb5f31650fb5ea846bcddf2ce2b50b1770fac4039`.
Actions artifacts `signed-library` `12281355410`, `central-deployment`
`12280977687` and `repository-consumers` `12279819488` expire at
`2027-01-18T16:10:38Z`; retain local originals beyond that date.
Never rebuild/resign this coordinate or move its component tag for recovery.

Tagged-source local `make verify` and `make pmd-analysis` passed at
`2026-10-20T13:49:05Z` and `2026-10-20T13:49:37Z`. Reviewed analyzer identities
remained 137 accepted raw SpotBugs/0 visible, CPD 22 and PMD 19 advisory, without
identity/membership/message drift. The resource watchlist was reviewed on the
identical pre-merge tree in L3-4. These historical measurements are distinct
from later documentation checks and do not claim a fresh dependency-security scan.

## Extraction outcomes and ownership

| Stage | Durable outcome |
|---|---|
| L3-0 | Eight public types, scalar field identity, five ECS descriptors, outcome/duration semantics, masking and same-thread MDC contract; arrays and global registration excluded |
| L3-1 | SLF4J-only library, strict descriptor/value checks, type retention after null, provider-returned builders, collision cleanup and executed public examples |
| L3-2 | IOC field/action catalogs and observer adapters stayed local; producer corrections closed OBS-9/OBS-10 with actual JSON regression evidence |
| L3-3 | Accepted [ADR 0045](../../docs/ADR/0045-reusable-slf4j-logging-contract.md), explicit backend ownership and an executable application-owned Boot recipe |
| L3-4 | Parent-independent POM, exact SLF4J dependency admission, isolated plain/Boot consumers, dependency/routing negatives and protected publication readiness |

The ECS analysis found invalid `event.type` representations and an explicit
`error.type` plus Throwable collision that could drop an appender record. The
IOC migration moved internal types to service fields, omitted inapplicable
classification/outcomes and let the Throwable encoder own its error object.
L3-1 also fixed a reproduced regex stack overflow on long dotted keys with
possessive repetition and a retained descriptor regression. Those findings are
historical, not newly open defects. The
[observability capability](../../docs/dev/observability.md) describes current IOC
integration. Strict logging exceptions still propagate; separate OBS-6 containment
remains in [KNOWN-ISSUES](../../docs/KNOWN-ISSUES.md).

The historical L3-0 probe ran 62 cases against substituted old source copies at
`662aa880587e11cea08204c250d89894abf1834c`, not a published API. Some cases asserted
baseline defects; passing them was not defect closure. Its seven files remain
in Git history and were archived to `.dev/logging-docs/retired-l3-0-probe/`
before retirement. `baseline.json` SHA-256 is
`15b83441bec29b7b1eb5533e153eea034422833228735e016df3c190aaaf6954`.
It was not rerun as current qualification. Normal library tests and IOC/Boot
regressions own the supported behaviors; no 62-case one-to-one promotion is claimed.

Product/library versions remain lockstep. The library supplies no provider,
encoder, starter, IOC catalog, diagnostics bridge or automatic context propagation.
The recipe supports synchronous mutable events only. Type/category arrays,
a deployed second service, collector/Elasticsearch, load, other providers and
asynchronous encoder transports remain outside this release's qualification.
