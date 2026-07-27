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
| `R030-BASE` | `in-progress` | [baseline](evidence/baseline.md) | `BASE-TESTS-04` test lifecycle and duration inventory |
| `R030-BUILD` | `planned` | [build-quality ledger](evidence/build-quality-ledger.md) | SpotBugs report baseline + CPD calibration |
| `R030-TEST` global controls | `planned` | [test-quality ledger](evidence/test-quality-ledger.md) | JaCoCo report-only baseline |
| `R030-SEC` | `planned` | — | Security gap analysis |
| `R030-LIB` | `planned` | [shared-code inventory](evidence/shared-code-inventory.md) | Candidate classification |
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

## 3. Active work items

| Work item | Goal | Scope | Stage | Owner | State | Evidence |
|---|---|---|---|---|---|---|
| `BASE-TESTS-04` | `R030-BASE` | Reactor test lifecycle and effectiveness inventory | Discovery reconciled; lifecycle classification next | AI agent | `analyzing` | [baseline](evidence/baseline.md), [test-quality ledger](evidence/test-quality-ledger.md#baseline-discovery-inventory) |

## 4. Deferred и blocked

| Work item/scope | Goal | State | Reason | Missing evidence/exit condition |
|---|---|---|---|---|
| — | — | — | — | — |

## 5. Update rule

Matrix обновляется после каждого analysis или implementation work item.
`verified` требует goal-specific DoD; наличие merged PR само по себе
недостаточно.
