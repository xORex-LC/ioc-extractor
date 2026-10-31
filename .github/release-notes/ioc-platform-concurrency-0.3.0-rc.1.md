# ioc-platform-concurrency 0.3.0-rc.1

[Русская версия](ru/ioc-platform-concurrency-0.3.0-rc.1.md) ·
[Consumer guide](../../docs/guides/libraries/concurrency.md)

Published and separately qualified from Maven Central and GitHub Packages on
2026-09-08. This record preserves the completed LIB-1 worknote's release facts;
the consumer guide describes the supported API.

## Immutable identity

| Field | Value |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-platform-concurrency:0.3.0-rc.1` |
| Source | `c0f0f12f74a01615605b11b74e7b523bbc116cf5` |
| Annotated tag | `ioc-platform-concurrency-v0.3.0-rc.1` |
| Release branch | `release-0.3.0` |
| Validation | [34222437897](https://github.com/xORex-LC/ioc-extractor/actions/runs/34222437897) |
| Successful recovery publication | [34233605995](https://github.com/xORex-LC/ioc-extractor/actions/runs/34233605995) |
| Central deployment | `84d86a68-9402-4181-a587-772d004ad8c6` |
| Signing fingerprint | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| Signed `identity.json` SHA-256 | `be2311c54f06420cf86a8e6943658214e240853620b78f184ca425a20cca371e` |

The signed manifest contains 20 repository files. Both destinations received
the original signed bundle. Recovery did not rebuild, re-sign or overwrite the
coordinate. Product/library versions remain lockstep under
[ADR 0029](../../docs/ADR/0029-release-branch-library-publication.md).
Snapshot publication and independent version cadence remain deferred.

| Primary file | SHA-256 |
|---|---|
| Main JAR | `30d8afdc0fc6f47dad39462e419eb86f89328557a0aea58a5a88b2da681a7ee8` |
| POM | `f24318a99d713614d8201022f252dfc18b817cc0b89c9d704b0494f84623a4f5` |
| Sources JAR | `d8abd93d141051ceab67b8f95320e877a44de2980e2a79bc218286ff5ac15264` |
| Javadoc JAR | `8e2ab2ca886265d4d51e22c8254fc08f7b859db6a6ca56320b2336967e05fefe` |

## Publication and recovery evidence

Protected validation built the exact source, ran the local consumer, matched
the signing key to the fingerprint and signed once before repository writes.
Central accepted the USER_MANAGED deployment above. First publication run
[34229094758](https://github.com/xORex-LC/ioc-extractor/actions/runs/34229094758)
published Central, then failed on an HTTP 302 while verifying GitHub Packages.

Recovery preserved the original validation bundle and deployment. Fixes
`fa5e5a8f`, `26e58cef` and `46510880` admitted safe HTTPS read redirects with
cross-origin authorization stripping, qualification after skipped recovery
builds, deployment-ID preservation and mandatory visible consumer artifacts.
Their CI runs `34231003678`, `34232060476` and `34233119093` passed.

Final run `34233605995` confirmed both repositories contain the original files
and executed separate empty-cache consumers. Both compiled and ran public
synchronous/asynchronous contracts. Central comparison found all 20 manifest
entries without missing or mismatched files. This closed `R030-LIB` and
`TEST-PUBLICATION-08` for this coordinate.

| Retained artifact | ID | GitHub artifact SHA-256 |
|---|---|---|
| Signed library bundle | `10058869990` | `753de2d5feb7fd6637265f10dc400e4da8935ef13ea67f7a94f0e91787f6448e` |
| Central deployment identity | `10058880886` | `ec7da392d73ac381087761a4f7b55754430d76d68d2fa47e87547e1367810296` |
| Both repository consumer logs | `10058905999` | `d7f04cede9cf8d4a2048f108a910ad6f06126ec2e22f589afecda7a0764e4be3` |

Recorded Actions retention expires on 2026-12-07. Downloaded evidence preserves
the manifest hash above; recorded GPG verification reported `GOODSIG` and
`VALIDSIG` for the admitted fingerprint. Keep retained evidence beyond Actions
expiration. The original key UID and POM use different email spellings; aligning
the UID at a later key rotation does not change this signed release identity.

## Pre-release hardening and scope

Owner: Platform coordination. The JDK-only twelve-type API was retained with
unchanged signatures. Direct IOC consumers are application, ingest adapter and
bootstrap; they are modules of one service. A planned `feeds-collector` remains
a future consumer with its own execution/coalescing requirements.

| Original finding | Resolved or documented behavior |
|---|---|
| `LIB-1-01` | `oldestAge` retains the original submission time when a queued task is dispatched |
| `LIB-1-02` | Reentrant guard telemetry keeps the outer execution visible and excludes nested frames from waiting callers |
| `LIB-1-03` | Synchronous exclusion uses a non-fair lock and promises no FIFO |
| `LIB-1-04` | Worker service must be dedicated, asynchronous and execute-or-throw; caller-runs/discard policies are unsupported |
| `LIB-1-05` | Capacity is per-key waiting work; dispatched work and global key count are separate |
| `LIB-1-06` | Observer runtime failures are contained; close initiates draining without joining; durable recovery is service-owned |
| `LIB-1-07` | Existing waiting suites have containing timeouts, failure-safe releases and bounded worker termination checks |

Pre-release qualification on 2026-09-07 passed 23 module tests and a 25-project
offline reactor. Module coverage was 199/218 lines and 48/64 branches. Reviewed
reports contained 116 accepted raw SpotBugs findings/0 visible, 21 CPD groups,
21 PMD policy findings and 29 watchlist findings, with none newly introduced
by this module. These historical counts do not describe a later reactor HEAD.

The design retained the existing small JDK composition: exact per-key locks
with cleanup, and serial queues over one shared dedicated pool. Raw locks,
one executor per key, Guava striping and Guava sequential executors would still
need registry/admission/telemetry/lifecycle work or change isolation. No new
dependency, umbrella artifact, starter, BOM or JPMS module was introduced.
The two reviewed guard `VO_VOLATILE_INCREMENT` identities remained accepted:
user counts mutate under same-key `compute`; depth derives from the owned lock.
No analyzer suppression, coverage floor or test-lifecycle exception changed.

Current usage and ownership are documented in the consumer guide and
[module reference](../../platform/platform-concurrency/README.md).
[ADR 0028](../../docs/ADR/0028-pilot-library-publication.md) records the original
publication decision; ADR 0029 supersedes its branch/tag selection. Historical
discussion and superseded setup proposals remain available in Git history.
