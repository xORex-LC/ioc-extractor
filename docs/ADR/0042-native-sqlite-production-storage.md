# ADR 0042: Retain Native SQLite production storage

- Status: Accepted
- Date: 2026-10-13
- Scope: Production storage choice and removal of the isolated candidate of
  [ADR 0041](0041-sqlite-staged-publication-experiment.md).

## Context

Configurable IOC routing, URL/IP cleanup, identity selection and bounded source
preparation are working independently of a versioned canonical backend. The
versioned SQLite experiments improved writer availability but added physical
copying, visibility resolution, recovery and cleanup costs. The complete Native
million-input strict document batch reached durable SMB completion in 909.48 s
with independent canonical/receipt/output checks. The giant versioned candidate
needed 1,934.65 s and subsequently failed a full-receipt diagnostic.
These workload-specific observations are not a universal performance budget;
[native capacity](../dev/native-capacity.md) preserves their identities and limits.

## Decision

Keep the improved Native SQLite implementation as the only supported canonical
backend. Remove isolated versioned publication, typed transfer/journals, alternate
receipt layouts, candidate migration/operation scopes and experimental tools/tests.
There is no adaptive backend dispatcher or additional database family in this
release. Shared matching/mutation sessions, writer admission, bounded source and
prepared-row workspaces, CSV-import staging, receipt streaming and Native fault
contracts remain.

Preserve Native authority and transaction scope: per-artifact document commits,
cross-artifact managed-import commits, dataframe-owned receipts/lifecycle/slots,
service-owned delivery coordination, and one effective UTC sampled after write
ownership. The proposed fresh-publication-time protocol of ADR 0041 is not
applied to Native as an incidental cleanup change.

Publish retained implementation and measurement facts in capability documents.
Delete the temporary release experiment bundle without moving it to another
repository archive. Keep historical ADRs as decision records; mark ADR 0041's
experiment concluded without activation.

## Consequences

The service avoids maintaining multiple canonical visibility/recovery models.
SQLite still serializes writers and cannot preempt a large atomic transaction;
workload sizing, admission and document granularity remain operational concerns.
Negative capacity screens and remaining limitations stay explicit. Changes to
UTC semantics, storage family or publication atomicity need their own decision.

Cleanup requires Native contract/integration checks, architecture boundaries,
full verify, reviewed SpotBugs/CPD/PMD scope changes and documentation checks.
Removing obsolete suites/findings updates exact inventories, not quality floors.
No installed database, service or transport namespace is changed by this decision.
