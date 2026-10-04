# ADR-0036: Sealed document preparation workspace

Date: 2026-10-05

Status: Accepted for CAP-4 implementation

## Context

Document preparation retained all artifact winners in Java collections. The
writer then copied those winners into confirmation commands and unbounded JDBC
receipt batches. Correct SQL alone cannot bound this working set.

Documents commit one artifact at a time. Managed imports instead commit the
complete delivery across artifacts. These boundaries must remain distinct.

## Decision

The application owns a preparation invocation through JDK-only workspace,
row-source and closeable cursor ports. The JDBC adapter spools document
candidates, original-observation dedup keys and final-key selection into one
private SQLite database per invocation. It always spills; there is no
document-size threshold that silently selects an unbounded implementation.

One factory-wide admission budget covers all live workspace caches and bounded
row codecs. Disk quotas include source snapshots, database and journal files.
Maximum field, encoded row and artifact catalog sizes bound individual items.
SQLite mmap is disabled and temporary sorting uses disk. No IDs are reserved
before the failure-policy checkpoint. Cursors preserve first key encounter
order; LAST_NONEMPTY replaces a whole row without moving its key.

The workspace pins the original source bytes and their SHA-256, the observation
identity and durable registration rank, processing policy fingerprint, format
version, ordered headers, deferred-ID metadata and ordered-field positions.
Every routed candidate is retained privately, including losing candidates;
selection never bypasses later validation or diagnostics. Sealing records
extraction counts, candidate/winner counts and a streaming checksum. Promotion
requires integrity validation of the entire seal before the first artifact.

Canonical writers consume repeatable owned row cursors, close them on every
path, reuse transaction mutation sessions and flush receipt batches within a
fixed bound. Canonical data, provenance, artifact commit markers and typed
receipt rows remain in the same artifact transaction. Import COALESCE
participants and cross-artifact atomicity retain their existing workspace and
receipt ownership; document IDs never become import delivery IDs.

## State and ownership

| State | Owner / permissible action | Failure disposition |
| --- | --- | --- |
| UNSEALED | Extraction invocation; append and reduce only | Delete on close; rebuild from the pinned driving source after interruption |
| SEALED | Workspace factory; immutable rows and pinned identity | Retain for same-observation retry; validate identity, quota and checksum before use |
| PROMOTING | Application writer; one artifact transaction at a time | Retain seal; check durable observation/artifact commit before reservation or replay |
| TERMINAL | Successful invocation, dry-run or policy rejection | Close cursors and delete private state |

Unexpected exceptions before seal delete incomplete private state. A rejected
failure-policy checkpoint disposes the seal without canonical promotion.
Successful promotion disposes it after all projections. A projection failure
retains the seal, although canonical commit cannot be undone. Process-local
leases prevent concurrent use and cleanup of a live workspace.

## Recovery matrix

| Interruption boundary | Required recovery |
| --- | --- |
| Before seal | Rebuild; no canonical side effects are possible |
| Seal tampered, source/rank/policy/header mismatch | Fail closed before any new mutation; never reinterpret private rows |
| After seal before checkpoint | Reevaluate the original diagnostic checkpoint; no promotion from rows alone |
| During artifact transaction | Roll back that artifact; reservation ranges remain burned |
| After one artifact commits | Same observation resumes; committed artifact result comes from its durable marker, including a STAGING whole-document receipt |
| After all artifacts commit | Complete canonical receipt permits recovery after workspace deletion |
| After commit before projection | Retry projection; never renew already committed rows for the same observation |
| Different retry run identity (ING-11) | Do not infer missing artifact completion from an incomplete receipt; require the stable driving observation identity, otherwise fail closed for an existing promotion pin |
| Import after canonical commit | Finalize from import receipt without CSV or document workspace |

This decision does not make document promotion globally atomic or remove
whole-text/extracted-occurrence retention. Those upstream buffers and operation
scheduling remain separately measured capacity work.

## Consequences

Preparation trades sequential private-disk writes for bounded Java working
state. Disk exhaustion becomes an explicit precommit failure. Private state is
recoverable work, not another canonical authority. Source retention and cleanup
must be observable and bounded, and tests must exercise real spill, corruption,
small quotas, cancellation, restart and receipt-only recovery.

Related: [ADR-0017](0017-diagnostics-first-class-outcome.md),
[ADR-0024](0024-managed-dataframe-import.md),
[ADR-0034](0034-required-router-processing-plans.md).
