# IOC aggregate artifact

[Русская версия](ru/ioc-aggregate.md)

`ioc_aggregate` collects IPv4, domains, URLs and file hashes from accepted
documents or explicitly configured CSV deliveries into one canonical artifact
and feed. It works in normal oneshot and daemon processing; it is not a separate
runtime mode or a later pass over the other artifact tables.

Several IOC types and source deliveries can share the feed. Each row still
represents one IOC: the service does not correlate an IP with a domain, resolve
DNS, join several carriers or infer that two observations describe one entity.

## Public fields and identity

The public column order is:

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

There is no hash-algorithm column; valid MD5/SHA-1/SHA-256 values have
32/40/64 hexadecimal characters respectively.

Here `url_match` contains address text, not mask-policy codes such as `u:hEX`.
`lower-host` lowercases the scheme/host while preserving path/query/fragment case;
it does not invent a scheme or reduce the URL to its host. Detection and structural
validation still follow the supported parser/regex contract, not a complete
Internet address grammar.

The four normalized carrier columns form canonical identity. `name` comes from document
source attribution or the explicitly mapped managed-import column. A later
durably registered delivery may replace `name`; an empty later name preserves
the existing value. Rows are not correlated across IP, domain and URL in this
release.
Thus the same carrier tuple under a new name updates one row; changing a carrier
creates a different identity. A URL and its separately observed bare host remain
two records. `name` is the selected attribution, not a stable ID or a list of all
feeds that have ever reported the IOC. The artifact has no reusable export slot.
Name preservation applies to a matched active row; expiry and reappearance
follow the ordinary new-lifecycle rules.
The one-carrier rule belongs to this mapping/import contract, not generic
storage. Allowing several carriers requires a reviewed versioned contract and
identity transition; relaxing cardinality alone does not implement correlation.

## Configure the artifact and route

The current classpath and production templates enable generation and define
the `ioc-aggregate` export profile. No new dependency/module installation is
needed beyond the normal bootable application. Managed import is independently
disabled. An existing installation keeps its external YAML: a new template does
not silently add missing entries to operator-owned lists.

| Configuration | Required relationship |
|---|---|
| `ioc.sink.artifacts` | Enabled `ioc_aggregate`, its path, six accepted types, columns and write policy |
| `ioc.processing.document-plan` / `ioc.processing.plans` | Selected plan routes observations to the artifact; shipped `original-document` uses branch `aggregate-original` with `default-view: original` |
| `ioc.artifact-identity.artifacts` | Composite four-carrier key, `record-key: ioc-aggregate-row-v1`, match key `ioc-aggregate-v1` |
| `ioc.export.profiles` | Profile `ioc-aggregate`, `output-mode: complete`, `artifacts: [ ioc_aggregate ]`; export and JDBC storage must be available |
| `ioc.dataframe-import` | Separate source/authority/contract catalog when CSV input is required |

This is **one artifact entry**, not a complete replacement application YAML.
Preserve all other entries and referenced plans when editing a candidate:

```yaml
- name: ioc_aggregate
  enabled: true
  path: "./dataframe/IOC_aggregate_generated.csv"
  accepts: [ IPV4, DOMAIN, URL, MD5, SHA1, SHA256 ]
  columns:
    - { name: name,       from: source.label }
    - { name: ip_address, from: value, when-type: IPV4, when: [ is-bare-ip ] }
    - { name: url_match,  from: value, when-types: [ IPV4, DOMAIN, URL ], when: [ is-address-with-detail ], transform: [ lower-host ] }
    - { name: host_match, from: value, when-type: DOMAIN, when: [ is-clean-host ], transform: [ lower-host ] }
    - { name: hash,       from: value, when-types: [ MD5, SHA1, SHA256 ], transform: [ upper ] }
  write-policy:
    duplicate-selection: last-nonempty
    selection-column: name
    fields:
      - { name: name, update: latest-registered, empty: keep-existing }
```

Boot replaces an overridden list in full; a partial indexed overlay is not an
edit-by-name operation. Validate the complete candidate using `ioc-config check`.
Keep aggregate on the original view when using host-cleanup routes for other
artifacts: changing its view changes future carrier values/identity, not existing
rows. See [processing routes](ioc-processing-routes.md).

To stop future aggregate writes, revise the enabled artifact, plan references
and export/import catalogs together and validate them. Disabling generation
does not delete existing canonical rows or rewrite old exports.

## Generate and consume a feed

For an isolated example, use a new working directory outside the installed
service namespace, a trusted application JAR and no external config overlays.
Save this as `sample.html`:

```html
<html><head><meta charset="utf-8"></head><body>
<h2>БИБ-0001</h2>
<p>192.0.2.10</p>
<p>Example.org</p>
<p>hxxps[:]//Example.org/Case.exe?Token=AbC</p>
<p>d41d8cd98f00b204e9800998ecf8427e</p>
</body></html>
```

Point `APP_JAR` at the actual JAR, then run:

```bash
APP_JAR=/absolute/path/to/ioc-app.jar
java -jar "${APP_JAR}" extract --source ./sample.html
java -jar "${APP_JAR}" export --profile ioc-aggregate
```

The four observations produce separate rows attributed to `БИБ-0001`: a bare
IP, clean host, full refanged URL and uppercase MD5. The URL does not produce an
extra derived host/IP row. SHA-1/SHA-256 use the same hash column when observed.
Normal extraction can also populate the other configured artifacts independently.

| Output | Consumer use |
|---|---|
| `dataframe/IOC_aggregate_generated.csv` | Mutable projection of canonical SQLite state; subsequent accepted runs accumulate/update it |
| `var/export/ioc-aggregate/<slice>/IOC_aggregate_generated.csv` | Completed immutable profile slice with manifest/availability marker; use the whole completed slice for delivery |

Export does not reparse documents or union other CSV files. A repeated request
may be skipped when the plan/revisions/bytes are already covered. A changed
public `name` advances revision; origin-only metadata or ordinary TTL renewal
does not. Profiles select artifacts, not per-name/source subsets. For SMB
delivery explicitly authorize this profile on the publish target; other profiles
do not include it automatically. See [export mechanics](../dev/artifact-export.md)
and [remote storage](remote-storage-sync.md).

With fixed lifecycle active, expiry removes active aggregate membership and
refreshes the mutable projection. Expiry alone does not force a new immutable
slice; consumers must account for the documented export/lifecycle cadence.
The public feed contains no validity deadline. See
[canonical lifecycle](canonical-record-lifecycle.md).

## Before the first upgraded start

For an existing installation, prepare an upgrade as one coordinated transition:

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

Known `OPS-10` can break import status/health on a pending delivery's nullable
deadline. A failed status query is not proof that intake is drained or canonical
commit failed; preserve the recovery point and follow the import runbook.

Startup fails closed when ordered-field policy is enabled while legacy
nonterminal work has no durable registration. Stop intake, restore the matching
pre-upgrade recovery point or run a configuration that does not enable the
ordered policy until the legacy work is drained. The application never assigns a
new rank to make such work pass recovery.

## Empty-start behavior

Upgrade does not scan existing canonical artifacts and does not synthesize
aggregate rows. On first introduction, the aggregate table and projection start empty and are populated
only by observations accepted after activation. Re-submit data deliberately if
historical population is required; that is a new delivery with a new precedence
rank and normal lifecycle effects.
An ordinary restart preserves existing aggregate state. Existing generated CSV
files and `source` samples are not read as runtime lookup inputs.

Retries and restart recovery keep the original rank. Completion order does not
decide the winner: a later registered nonempty `name` remains authoritative even
when an older delivery commits afterwards. Within one document or imported CSV
duplicate group, the last accepted nonempty name selects the whole occurrence;
later empty names do not clear it.
Document selection and ordered-field candidates use a nonblank test. A newly
extracted document row without a source label has name NULL.
Delivery registration, not source timestamps or import filenames, establishes
cross-path precedence; storage owns it in the dataframe DB.

## Managed import

The production template contains an `ioc-aggregate-v1` contract and
`ioc-aggregate-feed` authority while global intake is disabled. The classpath
defaults have empty import catalogs. Keep the full intended catalog when preparing
a candidate. Daemon intake requires fixed lifecycle and JDBC stores.

1. configure a dedicated local or SMB source and allow only the aggregate
   contract and authority profile;
2. validate the effective configuration;
3. preview representative files with an enabled import catalog and fixed lifecycle
   in a disposable oneshot configuration/storage copy before enabling live intake;
4. enable daemon intake only after inbox, permissions and retention are ready.

For a configured source ID, the installed CLI preview is:

```bash
sudo /opt/ioc-extractor/bin/ioc import validate \
  --source aggregate-local --file /path/to/candidate.csv
```

`aggregate-local` is an operator-chosen source, not a built-in source. Its
`contracts` must allow `ioc-aggregate-v1` and its `authority` must name
`ioc-aggregate-feed`. The shipped processed contract binds
`original-aggregate-import` with explicit input/output carrier fields, target-only
routing and no related-artifact authority. Preserve that named plan even while
intake is disabled: declared contracts are preflighted.

Example candidate rows:

```csv
name;ip_address;url_match;host_match;hash
Feed-A;192.0.2.10;NULL;NULL;NULL
Feed-B;NULL;https://example.org/Case.exe?Token=AbC;NULL;NULL
Feed-C;NULL;NULL;example.org;NULL
Feed-D;NULL;NULL;NULL;D41D8CD98F00B204E9800998ECF8427E
```

The input header is the five-column public contract. Exactly one carrier must be
nonempty in each accepted row. `name` uses `replace-non-null`; carrier values use
strict structural validation and cannot route to other artifacts. Duplicate rows
use last-nonempty selection before canonical promotion.
In the shipped contract, `row-failure-policy: reject-delivery` rejects a delivery
containing an invalid row. A compound IP+URL row is rejected, not split or joined;
missing rows do not delete existing data. `renew-unchanged: true` permits normal
lifecycle confirmation. No imported public ID/slot is used for this artifact.

Known limitation (`OPS-14`): an imported whitespace-only `name` is still a VALUE
cell. It can bypass ordered-field candidate handling and replace an existing name
through the ordinary `replace-non-null` merge. This violates the intended
nonblank-name preservation; the current implementation does not guarantee that
protection for whitespace input. Until fixed, use an empty cell or the configured
`NULL` token for an absent name, never padding spaces. Keep a nonblank real name
when supplying attribution. Track remediation in [known issues](../KNOWN-ISSUES.md).

Preview validates/maps individual rows, not duplicate reduction or active-DB
matching, and performs no canonical import. The full CLI may initialize storage
and workspaces. It requires enabled import; an empty/disabled catalog cannot
preview by source name. Preview exit is `0` for valid or `3` for invalid rows;
startup/configuration failures are separate. See [managed import](dataframe-import.md)
for stable local candidates, null/presence rules and real delivery submission.

## Health and retention

The `observationRegistration` health component exposes aggregate counts only.
An unresolved oneshot invocation reports `DOWN` with
`pendingOneshot` and the age of the oldest unresolved registration. Automatic
retention never deletes such a row because the process may have been performing
a long live invocation. Investigate the stopped invocation and preserve or
restore its coordinated recovery point; first establish whether the invocation
is still live. There is no automatic re-ranking fallback or permission to delete
internal rows to clear health.

Terminal document/import registrations are removed in bounded batches only
after the configured receipt/history horizon and only when no active/history
field origin, canonical observation receipt or service recovery reference still
uses them.

## Rollback boundary

The feature adds durable ordering and field-origin state to both databases.
Current supported versions belong to the
[dataframe migration catalog](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/DataframeFormatMigrations.java)
and [service migration catalog](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/ServiceSchemaMigrations.java),
not a fixed v11/v11 assumption. Do not delete tables or lower SQLite `user_version`.
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

## Sources and maintenance

- [Classpath defaults](../../bootstrap/ioc-app/src/main/resources/application.yml)
  and [production template](../../packaging/templates/application.yml) own the
  configured routes, identity, write policy, import contract and export profile.
- [Processing](../dev/processing.md), [storage](../dev/storage.md) and
  [ADR 0030](../ADR/0030-registered-observation-order-for-artifact-fields.md)
  explain preparation, public mutation and durable precedence.
- [Golden pipeline](../../bootstrap/ioc-app/src/test/java/com/iocextractor/GoldenPipelineIT.java),
  [customer routes](../../bootstrap/ioc-app/src/test/java/com/iocextractor/CustomerRoutingPipelineIT.java)
  and [export consumer](../../bootstrap/ioc-app/src/test/java/com/iocextractor/OnDemandExportIntegrationIT.java)
  qualify mapping and bytes. Run relevant suites and real isolated examples;
  `make docs` checks links, not semantics. Update both languages when behavior,
  configuration, ordering or compatibility changes.
