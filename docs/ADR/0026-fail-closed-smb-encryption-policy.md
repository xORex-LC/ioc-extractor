# 0026 — Fail-closed SMB encryption policy

## Status

**Accepted on 2026-08-28. The implementation is part of the 0.3.0 candidate;
live encrypted-endpoint qualification remains pending.**

This ADR narrowly supersedes the `encrypt: boolean` configuration and implied
encryption guarantee in ADR-0011. It does not change remote object ownership,
fetch/publish ledgers, managed-import semantics or the events-as-hints doctrine.

## Context

ADR-0011 selected SMBJ and exposed `ioc.sync.endpoints[].smb.encrypt`. The
implementation passed that boolean to `SmbConfig.withEncryptData`, while the
operator guides described the setting as SMB3 session encryption.

Those contracts were not equivalent. SMBJ's setting advertises a client
preference: encryption is used when an SMB3 dialect and encryption capability
are negotiated, but the default dialect set also permits SMB 2.0.2/2.1 and an
unencrypted fallback. The adapter did not inspect the authenticated session
before opening the share. Consequently `encrypt: true` proved intent, not the
documented guarantee.

A security control must distinguish an optional preference from a mandatory
transport policy. A mandatory policy must fail before remote share operations,
must not turn a deterministic mismatch into a retry storm, and must apply to
ordinary sync, managed import and dedicated CHANGE_NOTIFY sessions alike.

## Decision

### 1. Replace the boolean with a closed policy

The endpoint property is `ioc.sync.endpoints[].smb.encryption` with three
values:

- `required` — allow only SMB 3.0/3.0.2/3.1.1 and reject the connection unless
  the authenticated session will encrypt packets;
- `preferred` — request and prefer SMB3 encryption while explicitly allowing
  an unencrypted SMB2/3 fallback;
- `disabled` — do not advertise the client encryption preference.

`required` is the built-in and production-template default. Omission is secure;
using `preferred` or `disabled` is an explicit operator acceptance of the
network trust boundary.

The removed `encrypt` key remains unknown to the strict configuration shape.
The configuration failure analyzer provides a value-free migration hint:
operators choose `required`, `preferred` or `disabled` rather than receiving a
silent compatibility mapping.

### 2. Enforce `required` after authentication and before share I/O

For `required`, the SMB adapter configures an SMB3-only dialect allowlist and
advertises encryption support. After authentication it asks the established
SMBJ session whether data encryption is effective. Only then may it issue the
tree-connect/share request.

Both checks are required. The dialect allowlist prevents SMB2 downgrade; the
session check prevents an SMB3 server without a successfully negotiated
encryption capability/key from satisfying the policy. The same connection
helper is used by pooled file operations and dedicated CHANGE_NOTIFY sessions,
so no secondary unverified path exists.

### 3. Report policy mismatch through a reusable transport outcome

A failed mandatory security negotiation is neither bad credentials nor a
transient network failure. The application transport taxonomy therefore owns
`SECURITY_POLICY_UNMET` with terminal `FAIL` disposition. Diagnostics map it to
`SYNC.SECURITY_POLICY_UNMET`; the SMB-specific reason remains inside the
adapter.

This outcome is reusable by future transports with mandatory TLS, host-key or
mutual-authentication policies and does not introduce SMB types into the
application layer.

### 4. Qualify both configuration and effective negotiation

Automated tests cover selector parsing, secure defaulting, legacy-key failure
analysis, SMB3-only dialect construction, allowed fallback modes and terminal
rejection of an ineffective required session.

The opt-in live SMB contract uses `required`; a successful first share request
therefore proves that the adapter passed its effective-encryption gate. Release
evidence must record the server family and encryption policy. An unencrypted or
SMB2-only negative stand remains desirable qualification evidence but is not
simulated as an external pass when unavailable.

## Consequences

- Existing `encrypt` configurations must migrate before startup.
- The production default no longer connects to SMB2-only servers. An operator
  may select `preferred` or `disabled`, but the weaker boundary is explicit.
- A server advertised as supported under `required` must pass live encrypted
  negotiation; ordinary file-operation success alone is insufficient evidence.
- Endpoint retry logic does not retry a deterministic security-policy mismatch.
- SMBJ remains confined to `adapter-transport-smb`; configuration parsing stays
  in bootstrap and retry/diagnostic semantics stay transport-neutral.

## Rejected alternatives

### Keep `encrypt: boolean` and reinterpret `true`

This could enforce encryption, but it would preserve an ambiguous public name
that cannot express an intentionally permitted fallback. Operators could not
distinguish compatibility from a security requirement.

### Trust server-side `encryption required`

Server enforcement is valuable defense in depth but is outside application
authority and varies across Samba, Windows Server and NAS deployments. The
client must enforce its own configured policy.

### Treat negotiation failure as authentication or transient I/O

Both classifications are misleading. Authentication may have succeeded, and
retrying the same incompatible endpoint cannot satisfy the policy.

## Open qualification work

- Run `SmbEncryptionContractTest` against the approved Samba stand with server
  encryption required and retain the exact command/result.
- Run the same positive contract for every additionally claimed Windows
  Server/NAS family.
- When an approved negative stand is available, prove that `required` rejects
  SMB2-only and encryption-disabled endpoints while `preferred` follows its
  documented fallback contract.
