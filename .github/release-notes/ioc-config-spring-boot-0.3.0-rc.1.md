# ioc-config-spring-boot 0.3.0-rc.1

[Русская версия](ru/ioc-config-spring-boot-0.3.0-rc.1.md) ·
[Consumer guide](../../docs/guides/libraries/config-spring-boot.md) ·
[Restricted-context recipe](../../docs/guides/libraries/config-spring-boot-check.md)

Published to Central and GitHub Packages on 2026-10-24. The original GitHub
consumer passed. The original Central CI consumer failed despite the workflow's
green conclusion; corrected local Central consumers passed with unchanged signed
bytes. PR 19 integrated the routing/pipefail fixes. A protected CI recovery run
remains pending as [TOOL-8](../../docs/KNOWN-ISSUES.md).

## Immutable identity

| Field | Value |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-config-spring-boot:0.3.0-rc.1` |
| Source | `68679dd18ed251b71c4c0f4480b440a270dc1e71` |
| Annotated tag | `ioc-config-spring-boot-v0.3.0-rc.1` |
| Tag object | `d28e3d0c6f1e8bf48a77e97bb5bf846c1329e63b` |
| Release branch | `release-0.3.0` |
| Reviewed PR | [18](https://github.com/xORex-LC/ioc-extractor/pull/18), head `0764e711`; merged source has the same tree |
| Merge CI | [40086508961](https://github.com/xORex-LC/ioc-extractor/actions/runs/40086508961) |
| Validation | [40087127800](https://github.com/xORex-LC/ioc-extractor/actions/runs/40087127800) |
| Original publication | [40088095516](https://github.com/xORex-LC/ioc-extractor/actions/runs/40088095516) |
| Central deployment | `90e22519-418d-4b74-ac5a-cadcc3d2c559` |
| Signing fingerprint | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| Signed `identity.json` SHA-256 | `e57f1f52f53cc95ff5290943d9c5cd790928ea063fc726ca7388c7fe021f74f2` |

Protected validation passed clean RC build, strict Javadoc, 119 publication
contracts, local cold API/recipe consumer, default/application-BOM mediation,
dependency/routing negatives and PMD. The owner approved both protected reviews.
Publication reused the original signed bundle/deployment with build skipped.
The 20 signed/checksummed files and four primary artifacts remain immutable.

| Primary file | SHA-256 |
|---|---|
| POM | `26a24702b248c75559ddafb843e7335a026d9b843e36718846c549e808e6dfa8` |
| Main JAR | `07bf49086f3d551ed5b4f66c0a95d1ed9edf791550327cddf4230e3344dd673c` |
| Sources JAR | `42a5d492056dad80d58aaf09794315e9cf914053fcc9a8742f98037f501803d4` |
| Javadoc JAR | `f9177c01f661c73be7424c6139e4f7f082e70c3fb431f2c594074dfa04263f03` |

## Original failure and local recovery

Resolver 1.9.22 groups downloads by URL. Identical selected/public Central URLs
caused the own-group filter to reject required third-party Spring dependencies.
The workflow's default shell hid the failed `python | tee` pipeline and a later
successful GitHub command left the job green. That conclusion did not qualify
Central; retain the original failed consumer log.

The corrected tool admits the existing 105 reviewed public groups at selected
Central, while local/GitHub targets remain own-group-only and public routes reject
own groups. Each of four library artifacts must still record `library-target`
origin. The workflow uses `set -euo pipefail` for consumer pipelines. No runtime
dependency or group allowlist was broadened. Two regressions reproduce the old
routing/pipeline defects and require the original failure exit to propagate.

Local recovery passed the packaged API and unchanged closed-input Java recipe,
both dependency graphs, downgrade/unavailable-dependency negatives, missing-target
rejection and an unfiltered alternate-repository control. All four hashes and
origins matched the signed manifest. Original failure, GitHub success and local
Central recovery remain distinct evidence. Corrections recorded in `18ce6eae`
were integrated into `release-0.3.0` through PR 19; the old note's pending
integration statement is superseded by that merge, not by a new CI recovery run.

Recover only with validation run `40087127800`, the original tag/deployment and
signed files. Skip rebuild/signing and reuse matching bytes. Do not overwrite
this coordinate or substitute another source commit to repair qualification.

## Retained evidence and historical gates

Signed files, manifests, dispatch/workflow provenance, original consumer logs
and local recovery receipts are retained under
`.dev/configuration-release-0.3.0-rc.1/`. Local receipt `recovery-receipt.json`
binds tooling hashes, patch and recovery log separately from the library source.
Archive `signed-release.tar.gz` has SHA-256
`9b996a88e698155dfb9ea603f32b239da2652dd5ac5acdc552057b3564adc30e`.
Validation artifacts `signed-library` `12557752813` and `central-deployment`
`12557742874` expire on `2027-01-22T10:44:49Z`; retain local originals beyond it.

Tagged RC CI retained 137 accepted raw SpotBugs/0 visible, CPD 22 and PMD
19 advisory/0 blocking. Coverage integrity passed for 24 production groups and
23 local reports: 27121/29427 lines and 10289/12003 branches. These are historical
remote RC measurements, distinct from later local documentation checks.

## Extraction outcomes and ownership

| Stage | Durable outcome |
|---|---|
| C0 | 54 synthetic cases characterized pre-extraction IOC reflection, environment matching, Binder and declaration reporting; limitations were not generic API admission |
| C1 | Six public entry/result types, explicit record schema, name-only source traversal, immutable facts, coverage gaps and service-owned enforcement; migration observations preserve indexed paths |
| C2 | Explicit effective-Boot versus closed-input policies, restricted bean context, resource cleanup and actual list replacement; YAML/import/profile policy belongs to each service |
| C3 | Accepted [ADR 0046](../../docs/ADR/0046-reusable-spring-boot-configuration-inspection.md), module/consumer maps and executable documentation |
| C4 | Parent-independent POM with required Boot/Core dependencies, reviewed graph, default/application-BOM consumers, dependency and routing negatives, protected publication admission |

The JAR contains no starter, auto-configuration, YAML loader, validation runner,
IOC model, diagnostics or logging provider. Ordinary record/list/flat scalar-map
support, source coverage and migration limits are defined in the maintained
guide and Javadoc. A migration observer does not register aliases, convert old
values or establish conflicts between bound fields. Binder remains authoritative.

Controlled error adaptation stayed service-owned: CFG-5 removed rejected values,
parser/default exception text and unsafe dynamic paths from owned IOC reports.
[The failure-report contract](../../docs/dev/configuration-failure-reports.md)
states the remaining framework-log boundary. Indexed `spring.main.sources`
were rejected before extra bean instantiation; IOC retained effective imports,
explicit lifecycle flags and command-specific console suppression. The latter
is not a reusable embedded runner. Generic error-adapter, selector-token and
registry-helper admission still needs a real second use case.

The retired C0 probe compiled substituted copies of five old IOC classes, not a
published library. Its original baseline at `0df7183fcc02cfd010104733c3608e710b059be9`
and source remain in Git history. The 54-case `expected.tsv` SHA-256 is
`1fd211e9fb37909fe4cc94096b950ddf4430e91e398be482ba5a7c38f35d2aaf`;
`baseline.json` is `6771af953c50122ce176ce7140b4219958c3192ea52c0c5100195b2c64a58cb6`.
Its five files were copied to `.dev/config-docs/retired-c0-probe/` before removal.
Current public-API tests and the restricted-context fixture own supported behavior;
the historical probe was not rerun as current qualification.

Product/library versions remain lockstep. No other Boot baseline, second deployed
service, generic live reload or complete value/binding model is qualified.
Open protected CI recovery is tracked independently of publication success.
