# ADR 0030: Registered observation order for mutable artifact fields

Status: Proposed, 2026-09-22. P0–P2 implementation is authorized; activation
requires the remaining aggregate qualification and rollout decision.

## Context

Canonical rows currently use keep-first insertion. The new IOC aggregate has
one `name` per four-field IOC key, and a later delivery with a nonempty name
must replace an earlier one even if processing finishes in reverse order.
Document processing, managed import and oneshot share the dataframe database,
but their delivery ledgers have separate sequencing and recovery rules. File
ledger and JDBC service ledger are both supported daemon modes.

## Decision

The dataframe database owns a versioned, monotonic admission order and a random
namespace identifier. Registration is keyed by a stable delivery occurrence ID.
Registering the same occurrence again returns its original order; resuming a
missing registration fails. Neither timestamps, content hashes, file names,
worker completion nor a service-ledger sequence determine field precedence.

The service ledger owns document reservation, claim and recovery. Its JDBC and
file implementations satisfy one journal contract. The journal persists a
reservation before registration, then persists the returned reference before
claiming a token-named private file. Recovery adopts that file or fails on
contradictory evidence. Managed import uses its durable delivery ID after claim
reservation; oneshot uses an invocation ID and leaves a crashed registration
unresolved for explicit operator reconciliation. Dry-run does not register.

The field policy compares `(admission order, occurrence position)` within the
active canonical lifecycle. A newer nonempty value may replace an older one;
the same value advances provenance without changing the public row. Empty
values preserve the stored value and origin. Equal rank with conflicting
evidence fails. A public name change advances normal projection and export
revision; provenance-only changes do not. The field and its provenance commit
atomically in the dataframe database after the existing failure-policy
checkpoint. No transaction spans the service and dataframe databases.

The initial aggregate maps exactly one observed carrier per row. Its full
four-field non-name tuple is both record and match identity. The policy is
configured per artifact/field and does not alter existing artifacts' keep-first
behavior. Correlation of multiple carriers awaits an explicit input contract.

## Producer and recovery contract

Local producers publish a complete, closed file by atomic rename into the
inbox and never write it after publication. The consumer's quiet period is a
latency/stability hint, not proof of immutability. Before the registered claim,
capture available file identity and metadata; claim to a private token path;
hash the owned bytes and recheck identity/size/mtime before admitting them to
processing. If an available identity changes, a file is replaced at the same
path, or content changes during hashing, quarantine/fail closed. Where the
filesystem cannot supply sufficient identity, use an owned immutable snapshot
and verify its digest; never assign replacement bytes an earlier registration.
The complete P0 probe matrix includes same-size/same-mtime replacement and an
open writer surviving rename. A producer violating the handoff contract cannot
claim ordered processing based on quiet-period observation alone.

## Consequences

Dataframe registration can leave harmless order gaps after a rejected delivery.
The journal and terminal handshake retain recovery evidence until both stores
agree; cleanup must never turn an old retry into a new registration. A restored
service database paired with a different dataframe namespace fails before IOC
mutation. Existing unranked work must drain before activating the policy.
Rollback after schema migration requires a coordinated pre-upgrade restore;
binary-only downgrade is unsupported. This operational limitation and the
aggregate export profile/default activation remain release decisions.
