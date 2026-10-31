# ioc-platform-diagnostics-api 0.3.0-rc.1

[Русская версия](ru/ioc-platform-diagnostics-api-0.3.0-rc.1.md) ·
[Consumer guide](../../docs/guides/libraries/diagnostics.md)

Published and separately qualified from Maven Central and GitHub Packages on
2026-10-18. This record preserves the completed LIB-2 D0–D5 release evidence.
The maintained consumer guide and public Javadoc describe the supported API.

## Immutable identity

| Field | Value |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-platform-diagnostics-api:0.3.0-rc.1` |
| Source | `988df4af943fd61258850e6607bd547084763dbb` |
| Annotated tag | `ioc-platform-diagnostics-api-v0.3.0-rc.1` |
| Release branch | `release-0.3.0` |
| Reviewed PR | [16](https://github.com/xORex-LC/ioc-extractor/pull/16), head `662aa880`; the merged source has the same tree |
| Merge CI | [39132675560](https://github.com/xORex-LC/ioc-extractor/actions/runs/39132675560) |
| Validation | [39133285503](https://github.com/xORex-LC/ioc-extractor/actions/runs/39133285503) |
| Publication and both consumers | [39137777538](https://github.com/xORex-LC/ioc-extractor/actions/runs/39137777538) |
| Central deployment | `a48b06c1-c6e4-4a7b-b9b3-f9d1806be842` |
| Signing fingerprint | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| Signed `identity.json` SHA-256 | `1a2b7e96eadb62cb4be62d9b22b748728ca95f242c1b5043d84c82ff2908936b` |

The manifest covers 20 signed/checksummed repository files. Protected publication
reused the original validation run, deployment and signed bytes, with build
skipped. Both environment reviews were performed by the owner. Existing
concurrency coordinates and tags were preserved. No release may be rebuilt,
re-signed or overwritten to recover a partial publication.

| Primary file | SHA-256 |
|---|---|
| POM | `67cc035140a3a552e7a85f4a0fe5ead82c1aa1178b5e3e1deaee200f287d0fda` |
| Main JAR | `ec7c8295f7d372d6fac285027db9af57d6a934da625ac9568e183f813fe8ac37` |
| Sources JAR | `a431560fd6d308724e6c330783a3e92c4dcb63923d9ee3d19e3e78ac73285cd2` |
| Javadoc JAR | `d220c5372961ea72905a491cc969e7ebdea802e80e6bd833443185ce7dc6d9c9` |

## Publication qualification

Validation passed the clean RC build, strict Javadoc, 51 publication contracts,
local cold consumer, owner-key signing and Central validation. Signing left all
four primary artifacts unchanged. Publication completed Central release and
the GitHub Packages mirror, compared all 20 files and ran separate empty-cache
consumers outside the checkout. Both resolved POM/main/sources/Javadoc and ran
the three original guide scenarios with service-owned codes. An additional
anonymous Central download matched the four primary hashes independently.

| Retained artifact | ID |
|---|---|
| Signed library bundle | `12141056162` |
| Central deployment identity | `12140668942` |
| Both repository consumer logs | `12140822161` |

Recorded Actions expiry is `2027-01-16T14:50:17Z`. Local release bytes, dispatch
inputs, workflow logs, source verification and successful consumer logs remain
under `.dev/lib2-d5-publication/`, including `signed-release.tar.gz` and its
checksum. Retain original evidence beyond Actions expiration.

The artifact is available from the
[Central version directory](https://repo.maven.apache.org/maven2/io/github/xorex-lc/ioc-platform-diagnostics-api/0.3.0-rc.1/)
and the [GitHub Packages repository](https://maven.pkg.github.com/xorex-lc/ioc-extractor)
with authenticated package reads. The consumer guide gives normal Maven usage.

## Pre-release qualification

Maintainer: Misyurkeev Denis (`xORex-LC`). The final extraction and ownership
decision is [ADR 0044](../../docs/ADR/0044-reusable-diagnostics-contract.md).
Its eighteen-type JDK 21 contract uses one diagnostic model across IOC. Of the
former 38 diagnostics types, 17 moved after adaptation and 21 stayed local;
the resilient sink was adapted from the logging bridge. IOC categories,
catalogs, exceptions, rendering, context vocabulary and logging stay service-owned.

| Stage | Durable outcome |
|---|---|
| D0 | Fixed artifact/packages, maintainer, eighteen public types, metadata ownership, suppression-code input, severity-sensitive equality and compatibility |
| D1 | Extracted the shared model, migrated IOC, added public/package Javadoc and consumer examples |
| D2 | Qualified code identity, shallow ownership, counts, rejection signals, partial merges, delivery and IOC checkpoints; extended the API suite from 49 to 77 cases |
| D3 | Parent-independent POM, sources/Javadoc/license archives and isolated SNAPSHOT/RC consumers; explicit component selection retained concurrency's zero-dependency boundary |
| D4 | Accepted ADR 0044, reviewed all public members/packages, qualified sample order and publication path checks; 78 API cases and 51 publication contracts |
| D5 | Authorized integration, immutable tag/signing/publication and separate Central/GitHub consumers recorded above |

D2 reproduced a runner defect where reporting the terminal suppression summary
could attempt to suppress the same exception instance onto itself. The fix
preserves the original stage failure and one summary attempt, while distinct
delivery failures remain suppressed. Supported fallible delivery uses the
resilient sink. The failed regression remains in
`.dev/lib2-d2/runner-before-fix.log`; IOC pipeline tests own this service behavior.

D4 closed symbolic manifest/repository/recovery-path redirection before signing
or remote writes. Regression tests require rejection before a signing subprocess
or repository request and leave recovery targets untouched. A manifest records
hashes under trusted workflow provenance; its JSON is not a separately signed
attestation. Protected review remains part of release admission.

Local `make verify` and PMD passed on the clean tagged source at
`2026-10-18T13:56:29Z` and `2026-10-18T13:57:46Z`. Reviewed reports retained
137 accepted raw SpotBugs findings/0 visible, 22 CPD groups and 19 PMD advisory
findings, with no API drift. The D4 ownership watchlist retained 73 findings:
63 CloseResource, 8 PreserveStackTrace and 2 NcssCount. API coverage was
314/318 lines and 125/126 branches. No floor, accepted finding, suppression or
production analyzer/suite scope was weakened. These are historical measurements,
not results for a later worktree.

Product/library versions remain lockstep; during 0.x, breaking public contracts
need a minor version and migration notes. Another deployed production service,
wire/persistence compatibility, concurrent accumulators and throughput targets
remain outside qualification. Accepted decisions are preserved; superseded
planning discussion remains in Git history. New usage belongs in the EN/RU guide.
