---
title: "Матрица состояния релиза 0.3.0"
version: "0.3.0"
status: "Active"
document_type: "Execution status"
source_of_truth: false
language: "ru"
---

# Матрица состояния релиза 0.3.0

Правила состояний и переходов: [execution-model.md](execution-model.md).

## 1. Global goals

| Goal | State | Evidence | Next gate |
|---|---|---|---|
| `R030-BASE` | `verified` | [baseline](evidence/baseline.md), [compatibility ledger](evidence/compatibility-ledger.md) | Closed; re-baseline only by explicit scope/contract decision |
| `R030-BUILD` | `in-progress` | [build-quality ledger](evidence/build-quality-ledger.md) | `BUILD-CPD-02` report-only evaluation |
| `R030-TEST` global controls | `planned` | [test-quality ledger](evidence/test-quality-ledger.md) | `TEST-LIFECYCLE-01` taxonomy and lifecycle separation |
| `R030-SEC` | `planned` | — | Security gap analysis |
| `R030-LIB` | `planned` | [shared-code inventory](evidence/shared-code-inventory.md) | `LIB-1` concurrency-library admission record |
| `R030-DOC` | `planned` | — | Translation inventory |
| `R030-REL` | `planned` | — | All prerequisite goals |

## 2. Module hardening matrix

Все cells начинаются с `planned`: applicability подтверждается analysis.

| Maven module | `R030-QUAL` | `R030-RETIRE` | `R030-ARCH` | `R030-TEST` | affected `R030-DOC` |
|---|---|---|---|---|---|
| `platform/platform-errors` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-etl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-events` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-concurrency` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-observability` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics-logging` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-domain` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application-tck` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-regex-re2j` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-psl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-source-tika` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-sink-csv` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-manifest-json-jackson` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-store-jdbc` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-transport-smb` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-ingest` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-cli-picocli` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `bootstrap/ioc-app` | `planned` | `planned` | `planned` | `planned` | `planned` |

## 3. Work items

| Work item | Goal | Scope | Stage | Owner | State | Evidence |
|---|---|---|---|---|---|---|
| `BUILD-SPOTBUGS-01` | `R030-BUILD` | 19 production runtime JAR modules; fail-closed 23-project disposition registry keeps root/build-support POMs and reusable test-contract `ioc-application-tck` explicitly outside bytecode scope | Report-only tool introduction, per-module reports, reactor aggregate and 118-finding baseline; scope/report integrity blocks omissions, findings remain non-blocking | AI agent | `verified` | [SpotBugs rollout](evidence/build-quality-ledger.md#spotbugs-rollout) |

## 4. Deferred и blocked

| Work item/scope | Goal | State | Reason | Missing evidence/exit condition |
|---|---|---|---|---|
| — | — | — | — | — |

## 5. Update rule

Matrix обновляется после каждого analysis или implementation work item.
`verified` требует goal-specific DoD; наличие merged PR само по себе
недостаточно.
