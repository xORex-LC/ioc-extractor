---
title: "0.3.0 retirement inventory"
version: "0.3.0"
goal_id: "R030-RETIRE"
status: "Baseline intake ready"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-RETIRE — Retirement inventory

Contract: [R030-RETIRE](../goals/R030-RETIRE-retirement.md).

This inventory starts from [compatibility evidence](compatibility-ledger.md),
runtime wiring, repository history and build/test signals. One narrow
`remove-now` candidate is proven; its implementation remains a later,
separately verified `R030-RETIRE` slice. Other low-coverage and legacy-named
paths are protected by runtime/history evidence, while two dependency
candidates still lack semantic proof.

## Support obligations

| Surface | Supported source/version | Consumer status | Upgrade/rollback obligation | Owner |
|---|---|---|---|---|
| CLI/configuration | Released comparison `v0.2.0`; current command/typed-config authorities | Operators confirmed; named automation consumers absent | Explicit migration and matching previous binary/config rollback | `R030-QUAL`, `R030-DOC`, `R030-REL` |
| Diagnostics/logging/health | Current generated catalogs, nested ECS wire and loopback endpoints | Operators/deploy probes confirmed; named collector absent | Exact wire/query migration and matching probe/query rollback | `R030-TEST`, `R030-DOC`, `R030-REL` |
| SQLite/durable state | Dataframe v3, service v7 plus configured identity/schema | Operator durable state; tables are not third-party SQL API | Append-only upgrade; restore matching snapshot of both DBs on rollback | `R030-QUAL`, `R030-TEST`, `R030-REL` |
| CSV/export/manifest | Four mutable artifacts and strict complete manifest v1 | Downstream role confirmed; named reader absent | Version/schema migration; restore/reproject from matching canonical truth | `R030-TEST`, `R030-DOC`, `R030-REL` |
| Maven APIs/control events | No independently published modules; local events have no wire schema | Reactor-local consumers; `feeds-collector` only planned | Publication contract starts after `R030-LIB` admission | `R030-ARCH`, `R030-LIB`, `R030-TEST` |
| Packaging/deployment | Debian 11/12 marked-prefix interface | Operators confirmed | Immutable release + matching config/two-DB snapshot | `R030-DOC`, `R030-REL` |

## Candidates

| ID | Module/surface | Kind | Static evidence | Dynamic/wiring evidence | Consumer/history evidence | Disposition | Owner |
|---|---|---|---|---|---|---|---|
| `RETIRE-INGEST-1` | `LegacyLedgerImporter` and `legacy_imports` | `legacy-compatibility` | Production importer, migration table and focused tests exist | `AppConfig` eagerly runs it before poller start when JDBC ingestion ledger is selected | Replays file-ledger state into durable JDBC state; source-version exit window not defined | `keep-supported` | `R030-RETIRE` / ingestion owner |
| `RETIRE-SQL-2` | Accepted dataframe v1–v3 and service v1–v7 SQL migrations | `historical-evidence` | Append-only resources are loaded through `PRAGMA user_version` migrators | Applied before dependent storage operations; newer schema is rejected | Required for upgrade, audit and rollback reasoning | `keep-history` | Storage owner |
| `RETIRE-CFG-3` | `CONFIG.LEGACY_SYNC_TIMEOUT` classification | `legacy-compatibility` | Failure analyzer and regression tests recognize removed `read-timeout` shape | Strict preflight rejects the key and emits the migration action | Current removal-phase precedent for operator migration | `keep-supported` | Configuration owner |
| `RETIRE-REGEX-4` | `JdkRegexPatternEngine` | `unwired` candidate rejected | Class has no direct suite and 0 covered lines | `AppConfig.patternEngine` selects it for documented `ioc.engine=jdk` | Supported compatibility fallback; missing tests do not make it dead | `keep-supported` | Regex/bootstrap owner |
| `RETIRE-SMB-5` | `SmbjShareClient` and SMB change-notify implementation | `unwired` candidate rejected | Low/zero covered external seams; factory and contract tests reference them | `SmbFileTransport` opens the factory; live contract is conditional on external fixture | Remote SMB fetch/publish and optional push are supported | `keep-supported` | SMB owner |
| `RETIRE-MAINT-6` | `DaemonMaintenanceScheduler` | `unwired` candidate rejected | 0 covered lines in aggregate | Conditional daemon bean in `AppConfig`; documented maintenance lifecycle | Operator-configured retention capability | `keep-supported` | Bootstrap/maintenance owner |
| `RETIRE-PUBLISH-7` | `PublishLedger` default health aggregation | `dead-code` candidate rejected | Interface default path has zero covered lines | JDBC implementation, sync config, publish service, retention guard and health indicator consume the port | Durable delivery saga and health contract | `keep-supported` | Sync/storage owner |
| `RETIRE-TCK-8` | `ioc-application-tck` | `dead-code` candidate rejected | No local test suites and outside production coverage universe | Two abstract contracts execute through three adapter suites | Intentional build/test reuse for port implementations | `keep-supported` | `R030-TEST` |
| `RETIRE-DEP-9` | `ioc-application → ioc-platform-errors` | `unused-dependency` | Maven analysis reports declared-unused; no direct import found | Transitive/boundary intent not yet reviewed | Removing a direct edge can alter consumer POM and dependency closure | `defer-uncertain` | `R030-BUILD` |
| `RETIRE-DEP-10` | `adapter-sink-csv → commons-io` | `unused-dependency` | Maven analysis reports declared-unused; no direct import found | Resource/runtime/transitive use not yet semantically checked | External dependency only; no consumer need established | `defer-uncertain` | `R030-BUILD` |
| `RETIRE-APP-11` | `application.artifact.StableArtifactId` | `dead-code` | Repository-wide search finds only its declaration and package README; aggregate coverage is 0/1 line | No Spring/resource/serialization/reflection wiring; no Java consumer | Stable-id sidecar/port was retired; reactor modules are not externally published | `remove-now` | `R030-RETIRE` / application artifact owner |

Kind:

- `dead-code`;
- `unwired`;
- `deprecated`;
- `legacy-compatibility`;
- `unused-dependency`;
- `historical-evidence`.

Disposition:

- `remove-now`;
- `keep-supported`;
- `keep-history`;
- `deprecate-first`;
- `defer-uncertain`.

## Candidate detail template

### `RETIRE-<SCOPE>-<N>` — Title

- **Owner:**
- **Original purpose:**
- **Supported surface/source versions:**
- **Compile/dependency evidence:**
- **Runtime/resource wiring evidence:**
- **CLI/config/persistence/packaging evidence:**
- **External consumers:**
- **Migration/recovery/audit role:**
- **Disposition:**
- **Rationale:**
- **Removal/transition scope:**
- **Tests:**
- **Migration/rollback guidance:**
- **Missing evidence:**

## Completed removals

| Work item | Removed code/wiring/resources | Dependency check | Interface check | Tests/gates | Release notes |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

`RETIRE-APP-11` removal scope is the record plus its package README entry.
Verification requires no remaining symbol/reference, `core/ioc-application`
tests and the clean reactor gate. It must not be combined with artifact identity
or public-ID behavior changes.

## Kept history

| Candidate | Artifact/history | Why required | Owner | Validation |
|---|---|---|---|---|
| `RETIRE-SQL-2` | Dataframe/service versioned SQL resources | Source-version upgrade and recovery evidence cannot be reconstructed after deletion | Storage owner | Migrator tests + release upgrade/rollback stand |
| `RETIRE-INGEST-1` | `legacy_imports` checkpoints and file-ledger replay | Prevents duplicate/partial migration and preserves old durable ingestion fate | Ingestion/storage owner | Importer tests + supported-source transition decision |

## Uncertain

| Candidate | Missing evidence | Risk of removal | Owner | Exit condition |
|---|---|---|---|---|
| `RETIRE-DEP-9` | Exact direct API/boundary and future published closure need | Hidden transitive reliance or consumer POM drift | `R030-BUILD` | Source/resource/dependency-tree validation and clean consumer build |
| `RETIRE-DEP-10` | Exact resource/runtime/transitive use | Runtime CSV behavior or accidental transitive dependency change | `R030-BUILD` | Targeted adapter tests, dependency tree and clean reactor without coordinate |

## Excluded baseline signal

The ignored `adapters/adapter-lookup-csv/target/` Surefire XML found during test
reconciliation is local build residue from a removed non-reactor module. It is
not tracked production/history evidence and is excluded from release counts.
Workspace cleanup is not a retirement work item and does not authorize
repository source/history deletion.
