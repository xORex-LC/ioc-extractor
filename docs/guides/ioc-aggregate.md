# IOC aggregate artifact

This guide explains how to activate, consume and roll back the shipped
`ioc_aggregate` artifact. Its public CSV contract is:

```text
name;ip_address;url_match;host_match;hash
```

The artifact has no public `id`. Missing values are written as the configured
CSV null literal (`NULL` in the shipped configuration). Each current row carries
exactly one IOC value:

- bare IPv4 in `ip_address`;
- a full URL or a scheme-less host with path, query or port in `url_match`;
- a clean FQDN/domain without scheme, port or path in `host_match`;
- MD5, SHA-1 or SHA-256 in uppercase in `hash`.

The four carrier columns form canonical identity. `name` comes from document
source attribution or the explicitly mapped managed-import column. A later
durably registered delivery may replace `name`; an empty later name preserves
the existing value. Rows are not correlated across IP, domain and URL in this
release.

## Before the first upgraded start

The artifact is enabled in the 0.3.0 shipped sink and export profile. Managed
CSV import remains disabled until an operator configures and validates a source.
Prepare an upgrade as one coordinated transition:

1. Stop admitting new document and managed-import files on the old release.
2. Let all claimed document runs and import deliveries reach terminal state.
   Check daemon health, `ioc import status`, the document `processing` directory
   and the configured managed-import processing/staging directories. Do not
   infer old cross-path priority from timestamps.
3. Stop the service. Back up the effective configuration, the complete
   `var/db` directory including both SQLite databases and sidecars, and all
   service-owned processing/snapshot/staging files as one recovery point.
4. Install the new binary and reconcile the external YAML with the shipped
   template. Run `ioc-config check` before applying it.
5. Start the service and require healthy storage, lifecycle, ingestion and
   observation-registration components before restoring producer access.
6. Submit a new representative document and verify both
   `dataframe/IOC_aggregate_generated.csv` and an `ioc-aggregate` export slice.

Startup fails closed when ordered-field policy is enabled while legacy
nonterminal work has no durable registration. Stop intake, restore the matching
pre-upgrade recovery point or run a configuration that does not enable the
ordered policy until the legacy work is drained. The application never assigns a
new rank to make such work pass recovery.

## Empty-start behavior

Upgrade does not scan existing canonical artifacts and does not synthesize
aggregate rows. The aggregate table and projection start empty and are populated
only by observations accepted after activation. Re-submit data deliberately if
historical population is required; that is a new delivery with a new precedence
rank and normal lifecycle effects.

Retries and restart recovery keep the original rank. Completion order does not
decide the winner: a later registered nonempty `name` remains authoritative even
when an older delivery commits afterwards. Within one document or imported CSV
duplicate group, the last accepted nonempty name selects the whole occurrence;
later empty names do not clear it.

## Managed import

The production template contains a disabled `ioc-aggregate-v1` contract and
authority profile. Before enabling import:

1. configure a dedicated local or SMB source and allow only the aggregate
   contract and authority profile;
2. validate the effective configuration;
3. use `ioc import validate` on a representative file;
4. enable import only after its inbox, permissions and retention policy are
   ready.

The input header is the five-column public contract. Exactly one carrier must be
nonempty in each accepted row. `name` uses `replace-non-null`; carrier values use
strict structural validation and cannot route to other artifacts. Duplicate rows
use last-nonempty selection before canonical promotion.

## Health and retention

The `observationRegistration` health component exposes aggregate counts only.
An unresolved crashed oneshot invocation reports `DOWN` with
`pendingOneshot` and the age of the oldest unresolved registration. Automatic
retention never deletes such a row because the process may have been performing
a long live invocation. Investigate the stopped invocation and preserve or
restore its matching dataframe state; there is no automatic re-ranking fallback.

Terminal document/import registrations are removed in bounded batches only
after the configured receipt/history horizon and only when no active/history
field origin, canonical observation receipt or service recovery reference still
uses them.

## Rollback boundary

Service schema v11 and dataframe schema v11 are additive but are not supported
by older binaries. Do not delete internal tables or lower SQLite `user_version`.
Binary-only rollback is unsupported.

To roll back, stop intake and the service, restore the old binary and external
configuration, both SQLite databases and service-owned files from the same
pre-upgrade recovery point, then start and verify health before reopening
producers. Accepted work after that backup is lost from the restored state and
must be reconciled separately. Never combine a newer dataframe DB with an older
service DB, or the reverse.

## Related guides

- [Configuration reference](configuration.md)
- [Canonical record lifecycle](canonical-record-lifecycle.md)
- [Managed dataframe import](dataframe-import.md)
- [Deployment, upgrade and rollback](deployment.md)
