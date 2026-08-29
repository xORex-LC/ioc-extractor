# 0027 — Rollback-safe configuration evolution

## Status

**Accepted on 2026-08-29. The first compatibility window is implemented for
the SMB encryption selector in the 0.3.0 candidate.**

This ADR narrowly supersedes ADR-0026 where it requires the removed
`encrypt: boolean` key to fail immediately. It also refines ADR-0016's
tombstone-removal rule for a configuration change that crosses a packaged
binary rollback boundary. The fail-closed SMB policy, strict handling of every
unlisted key and all transport-layer ownership decisions remain unchanged.

## Context

Packaged deployment keeps `etc/application.yml` operator-owned while release
activation and rollback switch the application binary. That is normally the
right ownership boundary, but an immediate configuration rename creates an
impossible rollback sequence:

1. the old binary understands only the old key;
2. the new binary understands only the new key;
3. changing the shared file before activation breaks rollback to the old
   binary, while changing it afterwards prevents the new binary from starting.

Database and unit rollback cannot repair that configuration incompatibility.
Writing a `.new` template beside the live file informs the operator but does
not create an overlap in the accepted language.

ADR-0026 exposed this problem by replacing `smb.encrypt` with the closed
`smb.encryption` selector. The new selector is still necessary: a boolean
cannot distinguish mandatory encryption from an allowed fallback. What was
missing was a bounded expand/contract phase between the two public languages.

## Decision

### 1. Breaking configuration changes use an explicit expand/contract window

When an installed external configuration is shared by the active and rollback
binaries, a key rename or shape change must first ship an overlap release. A
compatibility alias is part of the typed bootstrap schema, is visibly marked as
temporary and is excluded from fresh templates. All unlisted keys remain
unknown and fail startup.

Compatibility is not inferred from similar names and is not implemented in an
adapter. Bootstrap owns binding, migration notices and semantic conflicts. The
consumer receives only the resolved current value.

The alias may be removed only after the supported rollback window no longer
contains a binary that requires it. Removal is a separate contract change with
release-note and operator-guide evidence.

### 2. Resolve the SMB alias deterministically

During the 0.3.0 compatibility window:

- `encrypt: true` resolves to `encryption: required`;
- `encrypt: false` resolves to `encryption: disabled`;
- if neither key is present, the secure `required` default applies;
- `encryption` retains its closed `required|preferred|disabled` grammar.

The mapping deliberately does not turn `true` into `preferred`: a legacy
operator request to encrypt must satisfy the current fail-closed guarantee when
the new binary is active. Rolling back to the old binary necessarily restores
the old binary's weaker historical behavior.

### 3. Reject ambiguous dual configuration

If `encrypt` and `encryption` are both present, startup fails in the collect-all
semantic preflight. Precedence does not choose between differently named
properties, because silently selecting either value would conceal a stale
configuration source.

Typed configuration retains raw presence until preflight completes and exposes
a separate resolved selector to composition. Configuration record constructors
therefore remain normalization-only and do not throw on operator mistakes.

### 4. Make compatibility observable without exposing values

Every effective external compatibility alias produces one startup warning with
a stable `CONFIG.*` code, the property name and its replacement. The value and
credentials are never logged. The migration catalog is general bootstrap
infrastructure; SMB-specific mapping remains in the endpoint configuration
model.

IDE metadata marks the alias deprecated. Current classpath defaults and
production templates show only `encryption`; guides document the temporary
alias and dual-key rejection.

### 5. Separate binary activation from final configuration contraction

For an existing installation whose rollback binary requires `encrypt`, deploy
the overlap release while keeping that key in the operator-owned file. After
the new release is accepted and the old rollback point is retired, apply a
separately validated candidate that replaces it with `encryption`.

Fresh installations and newly created endpoints use only `encryption`.

## Consequences

- A new 0.3.0 binary can start with the existing 0.2.x-style SMB key, and the
  deployment transaction can still return to the older binary without changing
  the shared configuration.
- Operators receive a warning until they finish the contraction step.
- A stale environment or CLI alias cannot silently override a current YAML
  selector; the dual presence is a startup error.
- Strict configuration remains strict: compatibility increases only by an
  allowlisted, tested alias and does not weaken unknown-key detection.
- The reusable annotation, migration catalog, value-free reporter and template
  exclusion rule can support future bounded migrations without placing product
  configuration knowledge in domain, application or transport modules.

## Rejected alternatives

### Migrate the live file before deploying the new binary

The previous binary does not understand the new selector, so automatic rollback
would restore a binary that cannot start.

### Let normal property precedence resolve both keys

The keys have different names and may arrive from different channels. Choosing
one hides stale state and makes rollback behavior dependent on source ordering.

### Keep all historical aliases indefinitely

Permanent aliases grow the public language, make mistakes harder to detect and
prevent strict configuration from serving as a reliable contract. Every alias
requires an explicit removal gate.

### Implement the alias in the SMB adapter

Adapters consume resolved transport settings and must not inspect Spring
configuration keys. Doing so would duplicate bootstrap policy and couple the
integration library to deployment migrations.

## Follow-up

- Retain the SMB alias while a supported rollback point still requires it.
- Record the release in which that rollback point is retired, then remove the
  alias, metadata warning and mapping in one contraction change.
- Use the same decision test for future breaking keys: if binary rollback and
  the external configuration do not switch atomically, ship an overlap first.
