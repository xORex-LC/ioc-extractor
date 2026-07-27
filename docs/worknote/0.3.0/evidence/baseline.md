---
title: "0.3.0 baseline evidence"
version: "0.3.0"
goal_id: "R030-BASE"
status: "In progress"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-BASE — Baseline evidence

Contract: [R030-BASE](../goals/R030-BASE-baseline.md).

## Work items

| Work item | State | Result/next gate |
|---|---|---|
| `BASE-SNAPSHOT-00` | `verified` | Clean starting revision selected |
| `BASE-ENV-01` | `verified` | Revision and execution environment captured |
| `BASE-REACTOR-02` | `planned` | Module graph and dependency inventory |
| `BASE-VERIFY-03` | `planned` | Fresh clean reactor verification |
| `BASE-TESTS-04` | `planned` | Test lifecycle and duration inventory |
| `BASE-COVERAGE-05` | `planned` | Measurement-only JaCoCo and coverage capture |
| `BASE-QUALITY-06` | `planned` | Warning, dependency and existing-control inventory |
| `BASE-RUNTIME-07` | `planned` | Representative performance/resource measurements |
| `BASE-CONTRACTS-08` | `planned` | Compatibility and consumer obligations |
| `BASE-INVENTORIES-09` | `planned` | Initial hardening inventories |
| `BASE-CLOSE-10` | `planned` | Evidence consolidation and goal closure |

## Revision и environment

| Field | Value |
|---|---|
| Branch | `release-0.3.0` |
| Commit | `fc4bcddf44dd6ed3d2d57f3a1167ec1e18db9fef` |
| Maven revision | `0.3.0-SNAPSHOT` |
| Released comparison tag | `v0.2.0` (`ad255040e73f589cb0b1fcab3581d836699e1888`) |
| JDK | Eclipse Adoptium `21.0.11` |
| Maven | Maven Wrapper, Apache Maven `3.9.9` |
| OS | Ubuntu `24.04.3 LTS`, Linux `6.6.87.2-microsoft-standard-WSL2`, `x86_64`/`amd64` |
| Locale/encoding | `en` / UTF-8 |
| Captured at | `2026-07-27T21:11:13+08:00` |

## Commands

| Command | Exit | Evidence/artifact | Notes |
|---|---:|---|---|
| `make context` | 0 | Inline `BASE-ENV-01` capture | Clean `fc4bcdd`; branch is one commit ahead of upstream; prior successful verification is stale |
| `make doctor-core` | 0 | Inline `BASE-ENV-01` capture | Bash, Java, Git, Make and Maven Wrapper checks passed |
| `./mvnw --version` | 0 | Inline `BASE-ENV-01` capture | Maven `3.9.9`; Eclipse Adoptium Java `21.0.11`; UTF-8 |
| `git rev-parse v0.2.0` | 0 | Inline `BASE-ENV-01` capture | Resolves released comparison tag to `ad255040e73f589cb0b1fcab3581d836699e1888` |
| `./mvnw clean verify` | TBD | TBD | |

## Module/dependency inventory

| Artifact/module | Packaging | Direct project dependencies | Production files | Test files | Notes |
|---|---|---|---:|---:|---|
| TBD | TBD | TBD | TBD | TBD | |

## Tests и coverage

| Module/scope | Test classes | Unit | Integration | Contract/architecture/E2E | Line | Branch | Missed branches | Duration | Flake status |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Reactor aggregate | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Per-module rows | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

Detailed per-module ratchets и dispositions ведутся в
[test-quality ledger](test-quality-ledger.md).

## Test lifecycle baseline

| Signal | Current state | Command/evidence | Target/disposition |
|---|---|---|---|
| Surefire-discovered tests | TBD | TBD | TBD |
| Failsafe-discovered tests | TBD | TBD | TBD |
| JUnit tags | TBD | TBD | TBD |
| Fixed sleeps/bounded waits | TBD | TBD | TBD |
| Disabled/quarantined tests | TBD | TBD | TBD |
| Slowest tests/suites | TBD | TBD | TBD |

## Quality reports

| Signal | Tool/version | Result | Artifact | Disposition |
|---|---|---|---|---|
| Compiler warnings | TBD | TBD | TBD | TBD |
| Static analysis | TBD | TBD | TBD | TBD |
| SpotBugs | TBD | TBD | TBD | TBD |
| PMD CPD aggregate | TBD | TBD | TBD | TBD |
| Dependency convergence | TBD | TBD | TBD | TBD |
| Maven dependency analysis | TBD | TBD | TBD | TBD |
| Security | TBD | TBD | TBD | TBD |

## Runtime/performance

| Scenario | Input/profile | Metric | Baseline | Environment | Command |
|---|---|---|---:|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

## Compatibility baseline

| Surface | Supported source/version | Known consumer | Upgrade/rollback path | Owner |
|---|---|---|---|---|
| CLI/config | TBD | TBD | TBD | TBD |
| SQLite/durable state | TBD | TBD | TBD | TBD |
| CSV/export/manifest | TBD | TBD | TBD | TBD |
| Maven API | TBD | TBD | TBD | TBD |
| Packaging/deployment | TBD | TBD | TBD | TBD |

## Controls inventory

| Control | State | Evidence | Owner | Follow-up |
|---|---|---|---|---|
| Maven Wrapper | `Existing` | TBD | TBD | |
| Maven Enforcer | `Existing` | TBD | TBD | |
| ArchUnit | `Existing` | TBD | TBD | |
| Reactor CI | `Existing` | TBD | TBD | |
| Documentation checks | `Existing` | TBD | TBD | |
| Dependency security workflow | `Existing` | TBD | TBD | |
| Release workflow | `Existing` | TBD | TBD | |
| JaCoCo report/check | `Missing at planning` | TBD | TBD | `R030-TEST` |
| Failsafe lifecycle | `Missing at planning` | TBD | TBD | `R030-TEST` |
| JUnit tag convention | `Missing at planning` | TBD | TBD | `R030-TEST` |
| Codecov | `Missing at planning` | TBD | TBD | `R030-TEST` signal |
| Scheduled stability run | `Missing at planning` | TBD | TBD | `R030-TEST` |
| SpotBugs | `Missing at planning` | TBD | TBD | `R030-BUILD` evaluation |
| PMD CPD aggregate | `Missing at planning` | TBD | TBD | `R030-BUILD` evaluation |
| Maven dependency analysis | `Missing at planning` | TBD | TBD | `R030-BUILD` evaluation |

## Missing evidence

| Item | Reason | Impact | Owner | Exit condition |
|---|---|---|---|---|
| — | — | — | — | — |

## Completion

- [x] Revision/environment fixed
- [ ] Clean verification captured
- [ ] Module/dependency inventory complete
- [ ] Tests/coverage captured
- [ ] Test lifecycle/tags/waits captured
- [ ] Quality reports captured
- [ ] Runtime/performance captured
- [ ] Compatibility obligations captured
- [ ] Controls classified
- [ ] Status matrix initialized
