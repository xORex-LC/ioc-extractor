# 0025 — Managed-import SMB service namespace and terminal retention

## Status

**Accepted on 2026-08-26. The contract is approved; implementation and live
qualification are pending.**

This ADR narrowly extends ADR-0024 for SMB managed-import namespace ownership,
capability admission and source-side terminal retention. It does not change the
read-only ordinary sync-fetch contract from ADR-0011 and does not make CSV a
second source of truth.

## Context

Managed dataframe import is not ordinary remote fetch. It takes ownership of a
source occurrence by server-side rename, keeps a recoverable remote processing
object, and later moves that object to a terminal or quarantine namespace. This
requires mutation rights on a partially trusted SMB boundary.

The 0.3.0 candidate currently creates
`<source>/.ioc-managed-import/{processing,terminal,quarantine}` on demand. A
successful or rejected occurrence is moved into that namespace, while the
existing import retention path deletes only the protected local terminal unit,
workspace, local immutable snapshot, dataframe receipt and service-ledger row.
The remote terminal or quarantine object therefore has no bounded cleanup
authority. Replays also have no remote processing object, although the common
finalization path can still request a transport disposition.

These are two coupled boundary defects:

- directory creation proves neither least privilege nor producer isolation;
- deleting the last durable ledger authority before source-side cleanup would
  make a failed remote purge undiscoverable after restart.

The application cannot safely configure or audit arbitrary share, Samba, NTFS
or NAS ACLs. It can, however, require an operator-provisioned namespace and
prove the private-namespace operations that do not require consuming producer
data before admitting source files.

## Decision

### 1. Make the private SMB namespace an operator-provisioned boundary

Each SMB managed-import source uses this fixed namespace below its configured
source directory:

```text
<source>/.ioc-managed-import/
├── processing/
├── terminal/
├── quarantine/
└── probe/
```

All four directories must exist before the source becomes eligible for intake.
The application must not create them, create the share, change ACLs or attempt
to repair permissions. Missing directories are a capability failure, not a
request for runtime provisioning.

The operational authority split is:

- the producer may publish completed candidates into `<source>` but must not
  list, read, create, rename or delete inside `.ioc-managed-import`;
- the service identity may list and read candidates and perform the exact
  server-side moves needed to claim them;
- the service identity may create, read, rename and delete only its own
  tokenized regular files inside the private namespace;
- unrelated consumers receive no access to the private namespace.

Share permissions and filesystem ACLs combine according to the server family.
The operator owns both layers. A source must not share its private namespace
with a different configured source or a credential principal that represents a
different trust level.

### 2. Gate each SMB source on an executable capability probe

Configuration validation remains value-free and offline. Before the first
claim, the daemon runs an endpoint-serialized probe for each enabled SMB import
source while that source's intake is closed. The probe must prove:

1. connect and authenticate;
2. list/stat the source and every required private directory;
3. create a unique content-free token in `probe`;
4. read/stat the token;
5. server-side move it from `probe` to `processing` and then to `terminal`;
6. prove destination collision is rejected without replacement;
7. delete the exact terminal token and clean all probe artifacts.

The probe carries no IOC or producer data. It uses a reserved token grammar,
never trusts a listed path as a delete target, and cleans up best-effort after
every failed step. A failed cleanup remains visible as a typed source-health
fact. It deliberately does not create a token in the producer root because
that would require unnecessary create-child authority there.

The probe therefore does not prove a real candidate's share mode or the
source-to-processing rename. Every claim still compares candidate evidence and
fails closed at that boundary. Live deployment qualification uses the producer
identity to publish a fixture and the service identity to claim it.

This positive probe does not prove that the producer is denied access. The
operator must run the negative producer-identity checks from the deployment
runbook. The application must not claim to audit server ACLs.

A transient network/server failure leaves only that source closed and
`DEGRADED`, with bounded retry. A deterministic missing-directory,
authorization, collision-semantics or object-type mismatch leaves that source
closed and `DOWN` until a later probe succeeds. The process may continue to
serve unrelated sources and recovery work; remote unavailability alone is not
a reason to abort the JVM. Invalid local configuration still fails startup
through the existing strict configuration boundary.

Capability evidence is invalidated after an authorization/capability failure
or an endpoint generation change that could alter share semantics. Periodic
listing remains the intake correctness path after admission; CHANGE_NOTIFY is
still only a latency hint.

### 3. Generalize at the semantic boundary, not at the SMB boundary

The hardening is implemented through transport-neutral application contracts.
It must not create an SMB-specific retention service, readiness coordinator,
delivery token algorithm or local snapshot implementation.

The target responsibility split is:

- source access detects, claims, revalidates/reads and disposes an occurrence;
  local filesystem and SMB adapters implement their own ownership mechanics;
- a source-capability port returns one application-owned readiness result;
  adapters implement the operations that are meaningful for their transport;
- one import snapshot store durably publishes, verifies, resolves and purges
  private local immutable snapshots for every source transport;
- one import retention service owns outcome eligibility and the ordered cleanup
  state machine;
- a terminal-source-retention port purges an optional transport-owned terminal
  remnant;
- the protected local terminal store deletes or archives the common
  source/report replay unit.

The names above describe capabilities rather than required Java type names.
Application services and values remain in `ioc-application`; shared keyed
serialization remains in `platform-concurrency`; filesystem persistence stays
in a local-filesystem adapter; SMB session, path, status and share semantics
stay in `adapter-transport-smb`. No adapter depends on another adapter.
Capability, claim, disposition and purge use the adapter's existing shared
`SmbSessionPool`, share-client primitives, exception mapping and
CHANGE_NOTIFY infrastructure; this hardening must not create a second SMB
connection or error-classification stack.

The immutable managed-object identifier is derived once from the delivery ID by
an application-owned value/factory and is reused by local and SMB adapters. The
adapter-stable candidate token remains detection/claim evidence and must not be
reused as a cleanup locator: it may encode producer-controlled metadata and has
different semantics.

Extraction of that value preserves the candidate's existing lowercase
SHA-256-of-UTF-8-delivery-ID formula byte-for-byte, so already claimed local and
SMB objects remain adoptable. Consolidation changes code ownership, not durable
identity. Similarly, a common snapshot store must continue to resolve existing
`local-snapshot-v1:` and `smb-snapshot-v1:` references during recovery and
retention; it must not rewrite or discard pinned evidence in place.

The terminal-source-retention command contains only stable application value
objects: delivery ID, source ID, managed-object ID and expected terminal
outcome. It contains no SMBJ type and no caller-supplied local or remote path.
The SMB adapter reconstructs the one allowed location from the configured
source, fixed namespace, validated managed-object ID and expected outcome.

The application cleanup service does not branch on `SMB`. A composed source
adapter reports whether forward disposition left a terminal source remnant. A
local occurrence can report no separate remnant because the protected local
terminal unit is already its retained evidence; SMB reports the tokenized
remote terminal/quarantine remnant. The same application state machine handles
both.

The application-owned materialization use case composes source access with the
snapshot store. A source adapter supplies claimed bytes and before/after source
evidence through application/JDK contracts; the snapshot adapter owns local
persistence. Neither adapter invokes or imports the other.

A replay is created from retained local evidence. It reports no transport-owned
source remnant and must skip remote disposition and source retention without an
SMB-specific conditional. Replay never reuses the original occurrence's
candidate token or remote locator.

The common age/count semantics continue to use the existing application
`RetentionPolicy` as their pure oracle, and endpoint/source scheduling continues
to use the existing keyed-concurrency primitives. The import cleanup saga stays
in the import application package because receipt and delivery-ledger ordering
are not a project-wide generic housekeeping contract.

### 4. Bound remote terminal evidence with the delivery ledger as last authority

The protected local source/report unit is the audit and replay authority. The
remote terminal/quarantine object is recoverable transport evidence, not a
second authoritative archive. It follows the same outcome age eligibility as
the local terminal unit:

- success uses the configured success retention window;
- partial/rejected outcomes use the configured failure retention window;
- local `archive` moves the protected local unit to its configured archive,
  but V1 still deletes the expired remote object;
- remote archive/cold tiering is out of scope.

For an eligible delivery, cleanup is ordered:

1. when disposition recorded a transport-owned remnant, purge its exact
   terminal/quarantine regular file; otherwise take the explicit no-remnant
   branch without calling a transport adapter;
2. delete or archive the protected local terminal unit;
3. delete the sealed workspace;
4. purge the private local immutable snapshot;
5. purge the dataframe import receipt;
6. compare-and-set delete the terminal service-ledger row.

If any step fails, later steps do not run and the ledger row remains the durable
retry authority. Every completed step is idempotent, so restart may repeat the
sequence safely. Expected absence of the exact remote managed-object ID is
success. An object in `processing`, a wrong outcome directory, an invalid
managed-object ID, a non-regular object or contradictory evidence fails closed
and is never deleted by retention.

The adapter exposes only exact regular-file deletion for this use case. There
is no recursive directory delete, wildcard delete, delete-by-listing-result or
arbitrary-path retention API. Processing-orphan recovery remains separate and
retention must never delete a processing object.

### 5. Preserve bounded coordination and safe observability

Capability, ownership, disposition and source-retention operations share the
existing logical endpoint serialization. Different endpoints may progress in
parallel. No new broker, timer per delivery or remote cleanup queue is
introduced: the terminal delivery ledger and periodic retention reconcile are
the correctness authorities.

Health, diagnostics and logs expose source ID, stable diagnostic code, probe
phase, terminal outcome, aggregate backlog and age. They do not expose host,
share, username, source path, filename, remote token, digest, raw exception or
IOC content.

Live qualification requires distinct producer and service identities. It must
prove both the positive service operations and the producer's inability to
enter the private namespace. Samba is the first reference family. Windows
Server and NAS families are explicit qualification skips until the same corpus
passes on those systems.

## Consequences

Positive consequences:

- intake cannot silently widen SMB authority by creating its own namespace;
- the application proves its actual mutation primitives before consuming
  producer data without pretending to be an ACL auditor;
- remote terminal/quarantine growth becomes bounded and restart-recoverable;
- a retained ledger row always explains and retries incomplete cleanup;
- replay no longer depends on a nonexistent remote occurrence;
- the application layer stays transport-neutral and SMB deletion remains an
  adapter-owned exact-object operation.

Costs and trade-offs:

- enabling SMB import now requires an explicit operator provisioning and
  negative-permission checklist;
- a source can remain closed while the daemon and unrelated sources are
  otherwise healthy;
- the capability probe briefly creates and moves reserved empty objects;
- remote and local terminal evidence may disappear at slightly different times
  during a retry, although durable state continues to converge safely;
- every newly claimed SMB server family needs live mutation and retention
  qualification, not only read/list compatibility.

## Rejected alternatives

- **Create directories at runtime.** This conflates application behavior with
  storage provisioning and cannot establish least privilege.
- **Attempt a full ACL audit over SMB.** Effective authorization is
  server-family-specific and includes identities the service cannot safely
  impersonate. Positive capability plus operator-run negative checks is the
  honest boundary.
- **Delete the remote object immediately after local snapshot publication.**
  This removes useful forward-recovery and operator evidence before terminal
  outcome.
- **Keep remote terminal objects forever.** This creates unbounded capacity and
  confidentiality exposure outside the declared retention policy.
- **Delete local evidence first and remote evidence later.** A crash can remove
  the final replay artifact while leaving an untracked remote copy.
- **Add recursive remote cleanup.** Broad deletion is unnecessary and turns a
  retention bug into a share-wide data-loss risk.
- **Add remote archive in V1.** It introduces a second archive authority,
  additional ACLs and another recovery saga without a stated consumer need.
- **Extend generic `FileTransport` with managed-import rename/delete paths.**
  Ordinary sync fetch is read-only; importing a mutating ownership contract
  into that abstraction weakens both boundaries.

## Implementation and validation follow-up

The implementation sequence and executable acceptance matrix live in the
versioned [SMB hardening worknote](../worknote/0.3.0/dataframe-import/smb-hardening.md).
Until those gates pass, this ADR is a designed contract rather than evidence
that the candidate already enforces it.

Primary protocol and server references used for qualification design:

- [MS-FSCC rename semantics](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fscc/3668ae46-1df5-4656-b481-763877428bcb);
- [Microsoft SMB security guidance](https://learn.microsoft.com/en-us/windows-server/storage/file-server/smb-security);
- [Samba `smb.conf` reference](https://www.samba.org/samba/docs/current/man-html/smb.conf.5.html).
