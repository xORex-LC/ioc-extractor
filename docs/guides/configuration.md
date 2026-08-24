# Configuration reference

This guide is the complete operator-facing configuration reference for
ioc-extractor. It describes supported settings, accepted values and operational
trade-offs without requiring knowledge of the implementation.

For a ready-to-edit production override, use
[the full application template](../../packaging/templates/application.yml).

## How configuration is applied

The application ships with safe built-in defaults. An installed service loads
`<prefix>/etc/application.yml` as an override, so omitted settings retain their
built-in values. Effective precedence, from lowest to highest, is:

```text
packaged defaults < external YAML < environment < JVM system properties < CLI
```

The complete `ioc.*` namespace is strict. A misspelled or removed property from
YAML, environment, a system property or the command line stops startup. This is
intentional: an operator must never believe that a safety or delivery setting was
applied when it was silently ignored.

Do not edit the installed YAML in place. Prepare a separate candidate and apply
it through the installed helper:

```bash
sudo cp <prefix>/etc/application.yml ./application.candidate.yml
sudo chown "$(id -u):$(id -g)" ./application.candidate.yml
${EDITOR:-vi} ./application.candidate.yml
sudo <prefix>/bin/ioc-config check ./application.candidate.yml
sudo <prefix>/bin/ioc-config apply ./application.candidate.yml
```

`check` is a side-effect-free syntax check. `apply` validates the exact staged
bytes again, replaces the live file atomically, restarts the service and waits
for health. A typed or semantic startup failure restores the previous YAML and
preserves the rejected candidate for diagnosis. Syntax failures use
`CONFIG.YAML_INVALID` with line and column but never echo the source line.

Secrets must be supplied through environment placeholders such as
`${SMB_PASSWORD}` and must not be written directly into YAML.

### Value syntax

- Durations use Spring syntax: `500ms`, `10s`, `5m`, `2h`, `7d`.
- Boolean values are `true` or `false`.
- Paths may be absolute or relative to the process working directory. The
  installed service uses the installation prefix as its working directory.
- Lists are YAML sequences or inline lists: `[ "*.html", "*.docx" ]`.
- Environment variables use uppercase underscore form, for example
  `ioc.pipeline.failure-policy` → `IOC_PIPELINE_FAILURE_POLICY`. Prefer YAML for
  indexed lists: partial list-element overrides can discard sibling fields from
  the lower-precedence source.

## Runtime and observability

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.runtime.mode` | `oneshot`, `daemon` | `oneshot` | Use `daemon` only for the long-lived systemd service. |
| `ioc.observability.mode` | `oneshot`, `daemon` | `oneshot` | Keep aligned with runtime mode. `daemon` enables rolling ECS JSON files. |
| `ioc.observability.per-item-trace-enabled` | boolean | `false` | Enables high-volume per-indicator TRACE events. Leave disabled unless diagnosing a bounded run. |

## Storage

The dataframe database is canonical business truth. The service database stores
ingestion, export and synchronization ledgers. SQLite databases create `-wal` and
`-shm` sidecars beside the main file; back up the entire `var/db` directory while
the service is stopped or after a coordinated SQLite backup.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.storage.service.type` | `jdbc` | `jdbc` | Only the JDBC/SQLite service store is supported. |
| `ioc.storage.service.url` | JDBC URL | `jdbc:sqlite:./var/db/ioc-service.db` | Keep separate from the dataframe DB. |
| `ioc.storage.service.sqlite.tuning` | `low-memory`, `balanced`, `high-throughput` | `low-memory` | Use `low-memory` on constrained/shared hosts, `balanced` with measured headroom, and `high-throughput` only on a dedicated high-memory host after load testing. |
| `ioc.storage.service.pool.write-max` | positive integer | `1` | Keep `1` for SQLite's single-writer model. |
| `ioc.storage.service.pool.read-max` | positive integer | `2` | Increase only after measuring concurrent ledger reads. |
| `ioc.storage.dataframe.type` | `jdbc` | `jdbc` | Canonical JDBC/SQLite storage. |
| `ioc.storage.dataframe.url` | JDBC URL | `jdbc:sqlite:./var/db/ioc-dataframe.db` | This file is business-data truth. |
| `ioc.storage.dataframe.sqlite.tuning` | `low-memory`, `balanced`, `high-throughput` | `low-memory` | Dataframe scans benefit most from larger presets, but account for JVM native memory and systemd limits before changing. |
| `ioc.storage.dataframe.pool.write-max` | positive integer | `1` | Keep `1` for SQLite. |
| `ioc.storage.dataframe.pool.read-max` | positive integer | `2` | Raise only after load testing. |

## Pipeline policy

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.pipeline.deduplicate` | boolean | `true` | Removes duplicates within one input batch. Cross-run deduplication remains enforced by canonical storage. |
| `ioc.pipeline.failure-policy` | `fail-fast`, `collect-and-continue` | `fail-fast` | Use `collect-and-continue` for unattended daemon ingestion; valid rows may commit while the run reports errors. |
| `ioc.pipeline.max-diagnostics-per-run` | positive integer | `10000` | Bounds memory and output volume. Lower it for small hosts or noisy untrusted inputs. |

## Source reading

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.source.type` | `auto` | `auto` | Detect the document format automatically. |
| `ioc.source.charset` | `auto` or Java charset name | `auto` | For legacy HTML/text, force values such as `windows-1251` only when detection is wrong. DOCX/PDF ignore this setting. |
| `ioc.source.section-markers` | list of RE2-compatible regexes | built-in Russian authority markers | A matching heading becomes the source label for following indicators. Order is preserved. |

## Refang and detection

Refang rules are applied in order. Keep more specific replacements before their
shorter prefixes. Detection patterns must remain RE2-compatible: do not use
look-around or back-references.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.refang.rules` | ordered list | built-in common defang replacements | Override the complete list when changing it. |
| `ioc.refang.rules[].from` | string | required per rule | Literal text to replace. |
| `ioc.refang.rules[].to` | string | required per rule | Literal replacement. |
| `ioc.engine` | `re2j`, `jdk` | `re2j` | Prefer `re2j` for linear-time matching. `jdk` is a compatibility fallback. |
| `ioc.patterns` | map keyed by `IPV4`, `DOMAIN`, `URL`, `MD5`, `SHA1`, `SHA256` | built-in patterns | Map iteration order is extraction priority. URL/IP should precede overlapping domain spans in effective processing. |

## Network classification

Rules use first-match-wins evaluation. An empty `when` list is the final fallback.
Registered predicates are `has-query`, `has-path`, `has-port`,
`has-path-or-port`, `is-ip`, `is-registrable`, `is-subdomain` and `is-onion`.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.classify.rules` | ordered list | four production rules | Override the complete ordered rule set. |
| `ioc.classify.rules[].when` | list of registered predicates | required; may be empty | Every predicate in a rule must match. |
| `ioc.classify.rules[].url-match` | non-blank string | required | Reputation-system URL match code. |
| `ioc.classify.rules[].host-match` | string or null | optional | Host match code; use null for URL-only variants. |

## CSV format and artifacts

The built-in artifacts are production defaults. Operators should normally change
paths, charset or enablement only. Schema and identity changes alter a durable
contract and must be tested against existing databases and consumers.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.sink.csv.delimiter` | one-character string | `;` | Must differ from quote. |
| `ioc.sink.csv.quote` | one-character string | `"` | Must differ from delimiter. |
| `ioc.sink.csv.null-literal` | non-blank string | `NULL` | Serialized value for absent cells. |
| `ioc.sink.csv.charset` | Java charset name | `UTF-8` | Unmappable characters are replaced and reported, not fatal. |
| `ioc.sink.artifacts` | non-empty list | masks, ip_list, address_blacklist, hashes | Override complete list elements, not isolated indexes. |
| `ioc.sink.artifacts[].name` | unique string | required | Stable artifact identity referenced by export and row-key configuration. |
| `ioc.sink.artifacts[].enabled` | boolean | required | Disabled artifacts are not prepared or projected. |
| `ioc.sink.artifacts[].path` | path | required | Mutable projection output path. |
| `ioc.sink.artifacts[].accepts` | list of IOC types | required | Values: `IPV4`, `DOMAIN`, `URL`, `MD5`, `SHA1`, `SHA256`. |
| `ioc.sink.artifacts[].include` | list of filter names | optional | All configured include predicates must match. Also supports `is-bare-ip`. |
| `ioc.sink.artifacts[].exclude` | list of filter names | optional | A matching predicate excludes the row. Also supports `is-bare-ip`. |
| `ioc.sink.artifacts[].id` | object | optional | Required when a column uses the deferred `id` provider. |
| `ioc.sink.artifacts[].id.strategy` | `ascending`, `descending` | `ascending` in defaults | Public ID allocation direction. |
| `ioc.sink.artifacts[].id.start` | `auto` or signed 64-bit integer | `auto` | `auto` continues after the durable baseline. Failed reservations are not reused. |
| `ioc.sink.artifacts[].columns` | non-empty ordered list | artifact-specific | Defines CSV header order and canonical table columns. |
| `ioc.sink.artifacts[].columns[].name` | non-blank string | required | Output column name; unique within an artifact. |
| `ioc.sink.artifacts[].columns[].from` | provider name | required | `id`, `value`, `source.label`, `match.url`, `match.host`, `address.url`, `address.ip`, or `const`. |
| `ioc.sink.artifacts[].columns[].value` | string or null | optional | Literal used by `const`. |
| `ioc.sink.artifacts[].columns[].type` | `TEXT`, `INTEGER`, `REAL`, `BLOB`, `NUMERIC` | inferred/text | SQLite affinity, mainly for constant columns. |
| `ioc.sink.artifacts[].columns[].when-type` | IOC type | optional | Emits the provider only for the selected IOC type. |
| `ioc.sink.artifacts[].columns[].transform` | ordered list | optional | Registered keys are `lower`, `lower-host`, `upper`, and `strip-prefix`; the parameterized form is `strip-prefix:<text>`. Not allowed on deferred `id`. |

## Canonical artifact identity

Every enabled sink artifact must have one matching identity entry. Key columns
must exist in that artifact's column list. Changing identity on a populated
database is guarded as schema drift.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.artifact-identity.artifacts` | non-empty list | one entry per built-in artifact | Keep names synchronized with sink artifacts. |
| `ioc.artifact-identity.artifacts[].name` | artifact name | required | References `ioc.sink.artifacts[].name`. |
| `ioc.artifact-identity.artifacts[].key-columns` | non-empty list | artifact-specific | Values forming the canonical row key. |
| `ioc.artifact-identity.artifacts[].key-mode` | `composite` or `first-non-empty` | `composite` | `composite` retains the complete configured tuple; use `first-non-empty` only for alternative-value columns. |
| `ioc.artifact-identity.artifacts[].epoch` | positive integer or omitted | omitted | Explicit identity-schema generation when a controlled migration requires it. |
| `ioc.artifact-identity.artifacts[].record-key` | versioned definition name | artifact-specific | Names the current canonical row-key definition; changing it alone does not migrate stored identity. |
| `ioc.artifact-identity.artifacts[].match-keys` | list of named column sets | artifact-specific | Declares versioned alternative keys used for active-record matching; a multi-match is rejected as a conflict. |
| `ioc.artifact-identity.artifacts[].match-keys[].name` | versioned definition name | required | Stable contract reference. |
| `ioc.artifact-identity.artifacts[].match-keys[].key-columns` | non-empty column list | required | Alternative active-record lookup tuple. |

## Managed dataframe import

Managed dataframe import is disabled in both shipped configurations. Enabling it
starts intake only after the shared startup recovery barrier has reconciled
ordinary ingestion and managed import. Keep `enabled: false` until the source,
authority and contract catalog has been qualified for the deployment.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.dataframe-import.enabled` | boolean | `false` | Master runtime/catalog switch; startup remains fail-closed until recovery completes. |
| `ioc.dataframe-import.runtime.dirs.processing` | path | `./var/import/processing` | Private transport-owned files after strict claim; keep on the same filesystem as each local inbox. |
| `ioc.dataframe-import.runtime.dirs.snapshots` | path | `./var/import/snapshots` | Private immutable byte snapshots; size and digest are pinned in the service ledger. |
| `ioc.dataframe-import.runtime.dirs.staging` | path | `./var/import/staging` | Per-delivery sealed SQLite workspaces; must not overlap another managed root. |
| `ioc.dataframe-import.runtime.dirs.terminal` | path | `./var/import/terminal` | Successful terminal delivery units. |
| `ioc.dataframe-import.runtime.dirs.quarantine` | path | `./var/import/quarantine` | Rejected or unsafe terminal delivery units. |
| `ioc.dataframe-import.runtime.detect.use-watch-service` | boolean | `false` | Optional local latency hint only; polling remains authoritative. |
| `ioc.dataframe-import.runtime.detect.use-change-notifications` | boolean | `true` | Optional SMB latency hint only; complete listing remains authoritative when notifications are disabled, lost or rejected. |
| `ioc.dataframe-import.runtime.detect.reconcile-interval` | positive duration | `5s` | Complete-listing correctness backstop for every configured source. |
| `ioc.dataframe-import.runtime.stability.quiet-period` | non-negative duration | `2s` | Required unchanged metadata interval before claim. |
| `ioc.dataframe-import.runtime.retry.delay` | non-negative duration | `5s` | Durable next-attempt delay; no detector or poller thread sleeps. |
| `ioc.dataframe-import.runtime.limits.maximum-snapshot-bytes` | positive bytes | `268435456` | Hard per-delivery snapshot limit checked while streaming. |
| `ioc.dataframe-import.runtime.limits.recovery-batch-size` | positive integer | `100` | Bound for one pre-promotion recovery pass. |
| `ioc.dataframe-import.runtime.retention.successful.max-age` | duration or omitted | `30d` | Maximum age of successful protected source/report units. |
| `ioc.dataframe-import.runtime.retention.successful.max-count` | non-negative integer | `0` | Maximum retained successful units; `0` disables count-based selection. |
| `ioc.dataframe-import.runtime.retention.successful.action` | `delete` or `archive` | `delete` | Action for an expired successful unit. |
| `ioc.dataframe-import.runtime.retention.successful.archive-dir` | path | none | Required protected destination when the successful action is `archive`. |
| `ioc.dataframe-import.runtime.retention.unsuccessful.max-age` | duration or omitted | `90d` | Maximum age of rejected/partial protected source/report units. |
| `ioc.dataframe-import.runtime.retention.unsuccessful.max-count` | non-negative integer | `0` | Maximum retained rejected/partial units; `0` disables count-based selection. |
| `ioc.dataframe-import.runtime.retention.unsuccessful.action` | `delete` or `archive` | `delete` | Action for an expired rejected/partial unit. |
| `ioc.dataframe-import.runtime.retention.unsuccessful.archive-dir` | path | none | Required protected destination when the unsuccessful action is `archive`. |
| `ioc.dataframe-import.runtime.retention.interval` | positive duration | `1h` | Independent bounded retention cadence. |
| `ioc.dataframe-import.runtime.retention.batch-size` | positive integer | `100` | Maximum terminal deliveries purged in one retention pass. |
| `ioc.dataframe-import.runtime.shutdown-timeout` | positive duration | `30s` | Bound for stopping change hints and allowing accepted lane work to reach a durable checkpoint. |
| `ioc.dataframe-import.sources` | list | empty | Managed local/SMB trust boundaries. Required when enabled. |
| `ioc.dataframe-import.sources[].id` | unique string | required | Durable source trust-boundary ID. |
| `ioc.dataframe-import.sources[].transport` | `local`, `smb` | required | SMB also requires an existing `ioc.sync.endpoints[].name`. |
| `ioc.dataframe-import.sources[].location` | transport-relative path | required | Dedicated managed-import location. |
| `ioc.dataframe-import.sources[].endpoint` | sync endpoint ID | SMB only | Omit for local sources. |
| `ioc.dataframe-import.sources[].contracts` | contract ID list | required | Exact source allowlist. |
| `ioc.dataframe-import.sources[].authority` | authority profile ID | required | Mutation ceiling for this source. |
| `ioc.dataframe-import.authority-profiles` | list | empty | Artifact/routing/formula/merge capability ceilings. |
| `ioc.dataframe-import.authority-profiles[].id` | unique string | required | Stable authority profile ID. |
| `ioc.dataframe-import.authority-profiles[].artifacts` | artifact name list | required | Maximum artifact allowlist. |
| `ioc.dataframe-import.authority-profiles[].maximum-merge-policy` | merge policy | required | Upper destructive mutation ceiling. |
| `ioc.dataframe-import.authority-profiles[].allow-related-routing` | boolean | `false` | Permits explicitly declared related branches. |
| `ioc.dataframe-import.authority-profiles[].allow-machine-only-formula-preserve` | boolean | `false` | Permits dangerous exact text only on a machine-only boundary. |
| `ioc.dataframe-import.contracts` | list | empty | Explicitly versioned recognition and mapping contracts. |
| `ioc.dataframe-import.contracts[].id` | unique string | required | Stable contract identity. |
| `ioc.dataframe-import.contracts[].version` | positive integer | required | Explicit behavior generation. |
| `ioc.dataframe-import.contracts[].charset` | supported charset | required | Strict decoder charset, normally `UTF-8`. |
| `ioc.dataframe-import.contracts[].dialect` | strict CSV dialect | required | Declares one-character delimiter/quote, record separator, required header and null literals. |
| `ioc.dataframe-import.contracts[].dialect.delimiter` | one character | required | CSV field delimiter. |
| `ioc.dataframe-import.contracts[].dialect.quote` | one character | required | Must differ from delimiter. |
| `ioc.dataframe-import.contracts[].dialect.record-separator` | `crlf-or-lf`, `lf`, `crlf` | required | Exact accepted record-separator policy. |
| `ioc.dataframe-import.contracts[].dialect.header-required` | boolean | must be `true` in V1 | Headerless import is rejected. |
| `ioc.dataframe-import.contracts[].dialect.null-literals` | exact string list | empty | Values interpreted as explicit `NULL`; an empty CSV cell is also null. |
| `ioc.dataframe-import.contracts[].recognition` | exact header signature | required | Required/optional/ignored canonical headers and aliases; file name and column order are not identity. |
| `ioc.dataframe-import.contracts[].recognition.required-columns` | non-empty list | required | All must occur exactly once after alias resolution. |
| `ioc.dataframe-import.contracts[].recognition.optional-columns` | list | empty | Allowed mapped headers that may be absent. |
| `ioc.dataframe-import.contracts[].recognition.ignored-columns` | list | empty | Allowed but deliberately unmapped headers. |
| `ioc.dataframe-import.contracts[].recognition.aliases` | string map | empty | External header to one declared canonical header. |
| `ioc.dataframe-import.contracts[].mode` | `as-is`, `processed` | required | No implicit preprocessing in `as-is`. |
| `ioc.dataframe-import.contracts[].routing` | `target-only`, `related-artifacts` | required | Related mappings also require source authority. |
| `ioc.dataframe-import.contracts[].row-failure-policy` | `accept-valid`, `reject-delivery` | required | Selects row isolation versus whole-delivery rejection. |
| `ioc.dataframe-import.contracts[].duplicate-policy` | `coalesce`, `keep-first` | required | Deterministic within-delivery duplicate handling. |
| `ioc.dataframe-import.contracts[].renew-unchanged` | boolean | required | Whether an accepted exact no-op confirms lifecycle validity. |
| `ioc.dataframe-import.contracts[].formula-policy` | `reject`, `machine-only-preserve` | required | Preservation requires explicit source authority. |
| `ioc.dataframe-import.contracts[].merge-default` | `keep-existing`, `fill-missing`, `replace-non-null`, `authoritative`, `reject-conflict` | required | Column/artifact overrides cannot exceed the source ceiling. |
| `ioc.dataframe-import.contracts[].artifacts` | mapping list | required | Exactly one `primary`; related entries require related routing. |
| `ioc.dataframe-import.contracts[].artifacts[].name` | artifact name | required | References a configured sink/identity artifact. |
| `ioc.dataframe-import.contracts[].artifacts[].role` | `primary`, `related` | required | Exactly one primary mapping per contract. |
| `ioc.dataframe-import.contracts[].artifacts[].record-key` | identity definition ID | required | Must equal the artifact's active declared record key. |
| `ioc.dataframe-import.contracts[].artifacts[].match-keys` | identity definition ID list | required | Every name must exist for that artifact. |
| `ioc.dataframe-import.contracts[].artifacts[].merge-default` | merge policy | optional | Artifact override below the source ceiling. |
| `ioc.dataframe-import.contracts[].artifacts[].columns` | mapping list | required | Target/source and ordered registered transforms; arbitrary code or SQL is not allowed. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].target` | artifact column | required | Unique target within the artifact branch. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].source` | recognized header | required | Required or optional canonical input header. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].transforms` | ordered transform list | empty | Registered transform specifications only. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].merge-policy` | merge policy | optional | Column override below the source ceiling. |
| `ioc.dataframe-import.contracts[].requested-slot` | optional mapping | omitted | Only artifacts with an external ID and a containing stable-slot export profile may use it. |
| `ioc.dataframe-import.contracts[].requested-slot.source-column` | recognized header | required when present | Positive requested external slot, never canonical identity. |
| `ioc.dataframe-import.contracts[].requested-slot.profile` | export profile | required when present | Scopes the external slot. |
| `ioc.dataframe-import.contracts[].requested-slot.existing-record-policy` | `preserve-existing`, `reject-mismatch` | required when present | Handles a survivor whose stable slot differs from the request. |

## Immutable export

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.export.enabled` | boolean | `true` | Controls manual and daemon export availability. |
| `ioc.export.root` | path | `./var/export` | Holds immutable slices, staging data and export ledgers. |
| `ioc.export.trigger.type` | `interval`, `quiet-period` | `interval` | `quiet-period` coalesces changes; periodic checks remain the recovery backstop. |
| `ioc.export.trigger.interval` | positive duration | `5m` | Scheduler/reconcile cadence. |
| `ioc.export.trigger.quiet-period` | positive duration | `5m` | Delay after the latest canonical change before export. |
| `ioc.export.trigger.max-cap` | positive duration | `1h` | Maximum postponement under continuous ingestion. |
| `ioc.export.profiles` | non-empty list | reputation-lists, address-blacklist | Ordered, indivisible artifact sets. |
| `ioc.export.profiles[].name` | unique string | required | Used by CLI and sync targets. |
| `ioc.export.profiles[].output-mode` | `complete`, `append` | `complete` | Only `complete` is executable in this release; `append` is reserved and rejected. |
| `ioc.export.profiles[].artifacts` | non-empty list | profile-specific | References enabled sink artifact names. |
| `ioc.export.retention.max-age` | duration or null | `7d` | Deletes unpinned slices older than this limit. |
| `ioc.export.retention.max-count` | non-negative integer | `3` | Keeps at most this many newest unpinned slices; `0` disables count limiting. |

## Remote synchronization

Remote sync is disabled by default. Credentials belong in the environment file.
See the [remote storage guide](remote-storage-sync.md) for permissions, topology,
failure behavior and value-selection examples.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.sync.enabled` | boolean | `false` | Master switch for the sync graph. |
| `ioc.sync.retry.max-attempts` | integer ≥ 1 | `3` | Total attempts for recoverable remote operations. |
| `ioc.sync.retry.backoff` | positive duration | `1s` | Initial retry delay. |
| `ioc.sync.retry.multiplier` | decimal ≥ 1.0 | `2.0` | Exponential growth factor. |
| `ioc.sync.retry.max-backoff` | duration ≥ backoff | `30s` | Caps retry delay. |
| `ioc.sync.retry.jitter` | boolean | `true` | Keep enabled when several instances may retry together. |
| `ioc.sync.endpoints` | list | empty | Named transport connections shared by fetch and publish. |
| `ioc.sync.endpoints[].name` | unique string | required | Stable reference used by sources and targets. |
| `ioc.sync.endpoints[].transport` | `smb` | required | Only SMB is currently supported. |
| `ioc.sync.endpoints[].smb` | object | required for SMB | Connection settings. |
| `ioc.sync.endpoints[].smb.host` | hostname or IP | required | SMB server; do not include `smb://`. |
| `ioc.sync.endpoints[].smb.share` | share name | required | Top-level share, without a path. |
| `ioc.sync.endpoints[].smb.domain` | string or null | optional | AD/NTLM domain; omit for local/workgroup accounts. |
| `ioc.sync.endpoints[].smb.username` | string | required | Prefer `${SMB_USER}`. |
| `ioc.sync.endpoints[].smb.password` | string | required | Use `${SMB_PASSWORD}`; never commit plaintext. |
| `ioc.sync.endpoints[].smb.encrypt` | boolean | `true` in template | Require SMB3 encryption when supported by the server. |
| `ioc.sync.endpoints[].smb.connect-timeout` | positive duration | `10s` | TCP connection establishment timeout. |
| `ioc.sync.endpoints[].smb.request-timeout` | positive duration | `30s` | Timeout for one SMB request. |
| `ioc.sync.endpoints[].smb.idle-timeout` | positive duration | `5m` | Close an unused cached client after this period. |
| `ioc.sync.fetch.enabled` | boolean | `false` | Enables remote-source detection and download. |
| `ioc.sync.fetch.interval` | positive duration | `1m` | Correctness polling cadence; never disabled by push notifications. |
| `ioc.sync.fetch.sources` | list | empty | Remote directories to monitor. |
| `ioc.sync.fetch.sources[].name` | unique string | required | Fetch source identity and ledger scope. |
| `ioc.sync.fetch.sources[].endpoint` | endpoint name | required | References a configured endpoint. |
| `ioc.sync.fetch.sources[].remote-path` | share-relative path | required | Directory containing source documents. |
| `ioc.sync.fetch.sources[].include` | glob list | empty | Accepted remote filenames. Empty means no include restriction. |
| `ioc.sync.fetch.sources[].exclude` | glob list | empty | Rejected filenames; exclusions win. |
| `ioc.sync.fetch.sources[].change-notify` | object | optional | SMB2 push accelerator; polling remains the backstop. |
| `ioc.sync.fetch.sources[].change-notify.enabled` | boolean | `false` | Enable only after server compatibility testing. |
| `ioc.sync.fetch.sources[].change-notify.debounce` | positive duration | `3s` | Coalesces bursts of change signals before re-detection. |
| `ioc.sync.publish.enabled` | boolean | `false` | Enables delivery of completed immutable slices. |
| `ioc.sync.publish.interval` | positive duration | `5m` | Publish-ledger reconciliation cadence. |
| `ioc.sync.publish.targets` | list | empty | Delivery destinations. |
| `ioc.sync.publish.targets[].name` | unique string | required | Publish ledger identity. |
| `ioc.sync.publish.targets[].endpoint` | endpoint name | required | References a configured endpoint. |
| `ioc.sync.publish.targets[].remote-path` | share-relative path | required | Parent directory for immutable slices. |
| `ioc.sync.publish.targets[].export-profile` | export profile name | required | References `ioc.export.profiles[].name`. |

## Daemon ingestion

The four ingestion directories must be distinct. Keep them on the same local
filesystem so claims can use atomic moves. Concurrency `1` is the supported
baseline for SQLite and deterministic file handling.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.ingestion.dirs.inbox` | path | `./var/inbox` | Operators place completed or still-copying source files here. |
| `ioc.ingestion.dirs.processing` | path | `./var/processing` | Claimed work owned by the daemon. |
| `ioc.ingestion.dirs.done` | path | `./var/done` | Successfully completed inputs. |
| `ioc.ingestion.dirs.failed` | path | `./var/failed` | Claimed inputs that failed terminally, retained for investigation and reviewed recovery. |
| `ioc.ingestion.patterns.include` | non-empty glob list | HTML, HTM, DOCX | Add PDF or other supported formats explicitly. |
| `ioc.ingestion.patterns.exclude` | glob list | temp/partial/hidden files | Exclusions win over inclusions. |
| `ioc.ingestion.detect.use-watch-service` | boolean | `false` | Keep polling for the production correctness baseline. `true` is an optional local-filesystem optimization: matching files rejected during `quiet-period` are retried, but OS event delivery is not a full directory-rescan guarantee. Do not enable it on network or unreliable filesystems. |
| `ioc.ingestion.detect.reconcile-interval` | positive duration | `30s` | Full directory scan cadence with the default polling mode; with WatchService it is the event/retry-queue poll cadence. Lower values reduce latency but increase scanning or wakeups. |
| `ioc.ingestion.detect.max-messages-per-poll` | positive integer | `50` | Bounds work claimed per detection cycle. |
| `ioc.ingestion.stability.quiet-period` | positive duration | `10s` | Increase for slow copies; too small risks reading an incomplete file. |
| `ioc.ingestion.retry.max-attempts` | positive integer | `3` | Attempts before moving a file to failed. |
| `ioc.ingestion.retry.backoff` | positive duration | `5s` | Delay between local ingestion retries. |
| `ioc.ingestion.ledger.type` | `file`, `jdbc` | `file` | Use `jdbc` when durable run-ledger integration is required; follow the deployment baseline. |
| `ioc.ingestion.ledger.path` | path | `./var/ledger` | Filesystem ledger location when type is `file`. |
| `ioc.ingestion.concurrency` | integer, exactly `1` | `1` | Parallel ingestion is not supported in 0.3.0. Startup rejects any other value instead of silently ignoring it. |

## Canonical lifecycle runtime safety

These controls select record validity, bound cleanup and convergence, and guard
against system-clock rollback. The classpath and upgrade presets remain
`disabled`; switching an existing database to `fixed` is a one-way operation.
Do not use zero as a cleanup command: durations and the batch size below must be
positive.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.lifecycle.validity.mode` | `disabled`, `fixed` | `disabled` | `disabled` preserves compatibility before activation. `fixed` enables durable validity and cannot later be disabled for the same database. |
| `ioc.lifecycle.validity.fixed-ttl` | positive duration | `12h` | Validity granted by each successful canonical confirmation. It is required and positive in `fixed` mode; `0` is rejected. |
| `ioc.lifecycle.validity.existing-records` | `reject`, `expire` | `reject` | Keep `reject` for the compatibility start. Explicitly select `expire` only for the activation restart; it archives and removes every legacy row and may leave all active projections empty. |
| `ioc.lifecycle.history-retention` | positive duration | `30d` | Retention of closed full-row lifecycle history; cleanup is independent and bounded. |
| `ioc.lifecycle.history-cleanup-interval` | positive duration | `1h` | Cadence for independent history/receipt cleanup discovery. Each transaction stays bounded; an existing backlog continues immediately in bounded follow-up tasks. |
| `ioc.lifecycle.receipt-retention` | positive duration | `30d` | Retention of complete prepared-row confirmation receipts. Missing, stale or policy-mismatched receipts cause ordinary ETL, never rejection. |
| `ioc.lifecycle.reconcile.backstop-interval` | positive duration | `5s` | Read-only nearest-deadline and projection correctness backstop for lost hints and restart. An empty deadline refresh does not create a reconcile cycle. Keep at or below `5s` for the V1 expiry-latency contract. |
| `ioc.lifecycle.reconcile.batch-size` | positive integer | `1000` | Maximum rows in one expiry or history-retention transaction; smaller values reduce writer hold time but require more cycles. |
| `ioc.lifecycle.clock.max-backward-skew` | positive duration | `2s` | Maximum system UTC rollback that may be clamped to the durable high-water and reported `DEGRADED`. |
| `ioc.lifecycle.clock.max-clamp-duration` | positive duration | `30s` | Maximum continuous clamp window before lifecycle time becomes unsafe and health reports `DOWN`. |

## Maintenance retention

Leaf-file retention applies to configured directories such as `done` and
`failed`. Immutable export slices have their own retention policy.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.maintenance.retention.enabled` | boolean | `false` | Enable only after backup and investigation requirements are defined. |
| `ioc.maintenance.retention.interval` | positive duration | `1h` | Sweep cadence. |
| `ioc.maintenance.retention.initial-delay` | non-negative duration | `5m` | Delay after daemon startup. |
| `ioc.maintenance.retention.targets` | list | done and failed examples | Policies for recursively discovered leaf files. |
| `ioc.maintenance.retention.targets[].name` | unique string | required | Operator-facing policy identity. |
| `ioc.maintenance.retention.targets[].dir` | path | required | Directory to sweep. Never point at a database or export root. |
| `ioc.maintenance.retention.targets[].max-age` | duration or null | target-specific | An entry is eligible when age or count limit is exceeded. |
| `ioc.maintenance.retention.targets[].max-count` | non-negative integer | `0` | `0` disables count-based retention. |
| `ioc.maintenance.retention.targets[].action` | `delete`, `archive` | `delete` | Archive is recoverable but needs additional storage. |
| `ioc.maintenance.retention.targets[].archive-dir` | path or null | omitted | Required for `archive`; must be outside the swept directory. |

## Supported platform settings

These Spring settings are part of the packaged operational contract. Other
Spring Boot properties are not automatically supported merely because the
framework recognizes them.

| Property | Built-in default | Guidance |
|---|---|---|
| `spring.profiles.active` | derived from observability mode | Installed daemon uses `daemon`. |
| `spring.config.additional-location` | launcher supplied | Keep the installed `optional:file:./etc/application.yml` location. |
| `spring.main.web-application-type` | `none`; launcher forces `servlet` for daemon | Launcher-owned; do not override in the installed YAML. |
| `logging.level.root` | `INFO` | Use `DEBUG`/`TRACE` only for bounded diagnostics. |
| `logging.level.com.iocextractor` | `INFO` | Application-specific verbosity. |
| `logging.file.path` | `var/logs` | Rolling ECS JSON log directory in daemon mode. |
| `server.address` | `127.0.0.1` | Keep loopback unless authentication and exposure policy are added. |
| `server.port` | `8081` | Actuator port used by health checks. `deploy-local.sh --port` and `install.sh --server-port` render a higher-precedence CLI override. |
| `server.tomcat.threads.max` | `8` | Sufficient for the loopback actuator-only surface. |
| `server.tomcat.threads.min-spare` | `1` | Minimum idle request threads. |
| `server.tomcat.accept-count` | `10` | Small pending connection queue. |
| `management.endpoints.web.exposure.include` | `health,info` | Do not enable shutdown or broad actuator exposure. |
| `management.endpoint.health.show-details` | `always` | Safe only while the endpoint is loopback-only. |

## Environment file

`<prefix>/etc/ioc-extractor.env` is read by systemd and the installed `ioc`
launcher. Supported entries are:

| Variable | Purpose |
|---|---|
| `JAVA_HOME` | Optional JDK override used by the launcher. |
| `JAVA_OPTS` | JVM memory, GC and diagnostic options. Do not put application CLI arguments here. |
| `SMB_USER` | Username referenced by `${SMB_USER}`. |
| `SMB_PASSWORD` | Password referenced by `${SMB_PASSWORD}`. The installer protects the file as `0640`, readable only by root and the service group. |

Use `systemctl restart ioc-extractor` after changing YAML or environment values,
then validate with `ioc health` and inspect `journalctl -u ioc-extractor`.
