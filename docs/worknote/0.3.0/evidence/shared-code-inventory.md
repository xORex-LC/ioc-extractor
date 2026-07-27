---
title: "0.3.0 shared-code inventory"
version: "0.3.0"
goal_id: "R030-LIB"
status: "Not started"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-LIB — Shared-code inventory

Contract: [R030-LIB](../goals/R030-LIB-shared-libraries.md).

## Existing modules

| Module/capability | Category | Responsibility | Current consumers | IOC-specific coupling | Publication disposition |
|---|---|---|---|---|---|
| `platform/platform-errors` | TBD | TBD | TBD | TBD | TBD |
| `platform/platform-diagnostics` | TBD | TBD | TBD | TBD | TBD |
| `platform/platform-etl` | TBD | TBD | TBD | TBD | TBD |
| `platform/platform-events` | TBD | TBD | TBD | TBD | TBD |
| `platform/platform-concurrency` | TBD | TBD | TBD | TBD | TBD |
| `platform/platform-observability` | TBD | TBD | TBD | TBD | TBD |
| `platform/platform-diagnostics-logging` | TBD | TBD | TBD | TBD | TBD |

## Publication candidates

| ID | Candidate | Category | Owner | Consumers | Dependency closure | State |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — |

## Admission record template

### `LIB-<N>` — Capability

- **Category:**
- **Cohesive responsibility:**
- **Owner:**
- **Existing consumer:**
- **Confirmed nearest consumer:**
- **Shared semantics/reason to change:**
- **Why JDK/external library/local placement is insufficient:**
- **Proposed public API:**
- **Excluded service-specific concepts:**
- **Direct dependencies:**
- **Project-owned transitive closure:**
- **External dependency budget:**
- **Versioning/compatibility policy:**
- **Sources/Javadoc plan:**
- **Contract tests:**
- **Standalone consumer:**
- **Publication repository/credentials:**
- **Disposition:**

## Messaging boundaries

| Concern | Owning artifact | Business schemas allowed | Broker/framework allowed |
|---|---|---:|---:|
| Local control events | `platform-events` | No | No |
| Generic messaging API | TBD | No | No |
| RabbitMQ adapter | TBD | No | Yes |
| Feeds integration contracts | TBD | Yes | No |

## Publication evidence

| Artifact/version | Protected workflow | POM | Sources | Javadoc | Checksum | Standalone consumer |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — |

## Rejected/deferred candidates

| Candidate | Disposition | Rationale | Owner | Revisit condition |
|---|---|---|---|---|
| — | — | — | — | — |
