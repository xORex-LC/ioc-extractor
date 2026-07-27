---
title: "0.3.0 retirement inventory"
version: "0.3.0"
goal_id: "R030-RETIRE"
status: "Not started"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-RETIRE — Retirement inventory

Contract: [R030-RETIRE](../goals/R030-RETIRE-retirement.md).

## Support obligations

| Surface | Supported source/version | Known consumers | Upgrade/rollback obligation | Owner |
|---|---|---|---|---|
| CLI/configuration | TBD | TBD | TBD | TBD |
| SQLite/durable state | TBD | TBD | TBD | TBD |
| CSV/export/manifest | TBD | TBD | TBD | TBD |
| Maven APIs | TBD | TBD | TBD | TBD |
| Packaging/deployment | TBD | TBD | TBD | TBD |

## Candidates

| ID | Module/surface | Kind | Static evidence | Dynamic/wiring evidence | Consumer/history evidence | Disposition | Owner |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — |

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

## Kept history

| Candidate | Artifact/history | Why required | Owner | Validation |
|---|---|---|---|---|
| — | — | — | — | — |

## Uncertain

| Candidate | Missing evidence | Risk of removal | Owner | Exit condition |
|---|---|---|---|---|
| — | — | — | — | — |
