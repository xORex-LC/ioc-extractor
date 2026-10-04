# CAP-0–CAP-1 execution evidence

Status: in progress, 2026-10-04. Scope is CAP-0, CAP-1A and CAP-1B from
the [capacity plan](data-processing-capacity-plan.md). The adopted budgets
remain acceptance targets. Later bounded-memory preparation and scheduling
stages are not implemented by this change.

## CAP-1A implementation checkpoint

`c7314427` changes ordinary request matching and staged import alias planning
to request-driven complete-key probes using SQLite `CROSS JOIN`. It preserves
the transaction visibility and import planning boundaries. No index migration,
bulk prematching or alias mutation policy change is included.

Nine real JDBC matcher/mutation regressions pass, including union of several
keys, stable ordering, equal digests with unequal material, strict expiry,
artifact/definition isolation, inserted and changed aliases visible within a
transaction, isolation from another connection and rollback.

A preliminary empty-store active-lifecycle run, one fork per workload:

| Input | Local processing | Caller allocations | Sampled current RSS peak |
|---|---:|---:|---:|
| Physical mixed document, 10k unique occurrences | 11.080 s | 2,592,641,872 B | 412,956 KiB |
| Physical processed import, 10k unique rows | 4.509 s | 1,273,593,872 B | 387,252 KiB |

This is a sanity sample, not paired improvement or budget acceptance. The
document profile uses original-view routing and one section; it does not
represent the stand's cleanup policy or 400-section incident fixture.

## Harness progress and remaining gates

The existing Make comparison facade now supports active-lifecycle capacity
profiles. The ordinary golden document profile previously left lifecycle
disabled and therefore did not exercise the stalled alias-matching kernel.
Capacity runs reject missing active metadata and use an independent oracle
for every public field and complete configured record key in all five
document artifacts. Actual results are read through a cursor after timing.
Production pipeline observer events supply stage durations; sampler failure,
process failure and missing phase anchors invalidate a run.

A 40-occurrence physical document and 40-row processed import passed in the
private CAP-1A snapshot. The document has six measured pipeline stages and
dataframe schema version 12. The independent oracle is also run against the
preliminary 10k document database; all five artifacts pass.

G0 remains open for full stand-policy AS_IS imports, explicit writer wait/hold
anchors and complete state manifests. G1A remains open for the full 100k
daemon/SMB cycle, version-pinned work screen and repeated scaling evidence.
CAP-1B will be compared to a frozen CAP-1A executable with identical probes,
configuration and physical inputs. Primary runs carry no Java agent or JFR;
diagnostic instrumentation remains a separate measurement mode.
