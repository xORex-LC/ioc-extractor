# Configuration reference

[Русская версия](ru/configuration.md)

This is the operator reference for supported settings, accepted values and
operational trade-offs. Start from the
[full production template](../../packaging/templates/application.yml).
The tables show built-in classpath defaults. Omission from an external file
retains effective lower-priority configuration; it does not necessarily select
an empty list or a Java constructor default.

The fresh production preset deliberately differs: daemon runtime/logging,
`collect-and-continue`, fixed `12h` validity with `existing-records: reject`, and
shorter detection/export cadences. Upgrades preserve operator-owned YAML and
write changed templates as `application.yml.new`; review and reconcile them.
Do not copy the fresh lifecycle preset into a populated database without the
[activation procedure](canonical-record-lifecycle.md).

## How configuration is applied

For the usual deployment sources, precedence increases from left to right:

```text
packaged defaults < external YAML < environment < JVM system properties < CLI
```

Classpath defaults import optional `./configs/application.yml`. The installed
unit additionally loads `<prefix>/etc/application.yml` from its working
directory. Boot imports and active-profile documents/files also participate.
`SPRING_APPLICATION_JSON`, when supplied, can override ordinary environment and
JVM properties; CLI still wins. Keep that input explicit when diagnosing a
setting that appears to ignore YAML.

The `ioc.*` namespace is strict, including declarations shadowed by another
source. A misspelled or removed key in loaded YAML, environment, JVM properties
or CLI stops startup. `IOC_*` is reserved; unrelated operator variables must
use another prefix. A syntactically valid YAML file can still fail conversion,
semantic constraints, registry/plan admission or later runtime checks.

Settings are read at startup; there is no general hot reload. Prepare a separate
candidate rather than editing live YAML. Set `install_prefix` to the existing
installation root, then run:

```bash
install_prefix=/srv/ioc-extractor
sudo cp -- "$install_prefix/etc/application.yml" ./application.candidate.yml
sudo chown -- "$(id -u):$(id -g)" ./application.candidate.yml
${EDITOR:-vi} ./application.candidate.yml
sudo "$install_prefix/bin/ioc-config" check ./application.candidate.yml
sudo "$install_prefix/bin/ioc-config" apply ./application.candidate.yml
```

`check [candidate.yml]` stages a service-readable copy and performs syntax,
strict-name, typed binding, semantic and registry/plan checks in a
configuration-only Spring context. Without a candidate it checks the installed
YAML. It runs as the service account, in the installation directory, with the
installed systemd EnvironmentFile and JVM overrides. Classpath defaults, the
candidate, allowed imports, profiles and effective overrides all participate.
A higher-priority override can therefore mask a candidate's value. Review the
effective sources when the outcome is surprising.

Successful checking prints `CONFIG.SEMANTIC_VALID` and exits `0`. It does not
open application databases, initialize transports, start intake or verify the
current database/policy, paths, remote access and recovery. Required imports
can perform I/O; the restricted bean graph is not resource isolation. Use the
installed helper for this short-lived check. The internal YAML-only parser's
`CONFIG.YAML_VALID` confirms syntax alone.

`apply` requires a separate regular non-symlink file. It takes an apply lock,
stages and validates the exact bytes again, backs up live YAML, atomically
replaces it, restarts the service and waits for `UP`. The original candidate
may be anywhere root can read; checking uses the staged service-readable copy.
Do not rely on relative imports beside the original candidate: Boot resolves
relative resources from the staged file's location under installed `etc`.
Use deployment-stable import locations and keep profile selection consistent.
A standalone check does not reserve its environment or candidate for a later
apply; apply performs a new check.

A rejected precheck leaves live YAML and the running service unchanged. If the
restart or health check fails after replacement, the helper preserves
`application.yml.rejected-<stamp>`, restores
`application.yml.backup-<stamp>` and attempts to restart the previous
configuration. Check its final result: restored YAML does not prove that the
previous service is healthy. File restoration does not undo database migration,
lifecycle activation, admitted work or remote effects. Before state-changing
settings, follow the owning backup/migration procedure.

The systemd unit repeats semantic checking in `ExecCondition`; a configuration
error with exit `78` skips activation without a restart loop. Use
`journalctl -u ioc-extractor` and the checker output to locate the failed phase.
See [deployment](deployment.md) and [packaging](../../packaging/README.md) for
application/database recovery.

Secrets belong in protected environment placeholders such as `${SMB_PASSWORD}`,
not literal YAML values. Controlled checker output excludes supplied values and
exception text; names and candidate paths can still be sensitive. Ordinary
framework DEBUG logs and arbitrary appenders are outside that guarantee.

### Value syntax

- Durations use Spring syntax: `500ms`, `10s`, `5m`, `2h`, `7d`. Bare numbers
  have context-dependent units; use explicit units. Each setting's positivity
  and bounds apply independently; zero is not a universal disable value.
- Boolean values are `true` or `false`.
- Paths are absolute or relative to the process working directory; the installed
  service uses its installation prefix. That does not relocate Boot's relative
  imports, which follow the importing configuration resource.
- Lists use YAML sequences or inline lists: `[ "*.html", "*.docx" ]`. The
  highest-priority source defining a list replaces the whole list, including
  element fields and lower-priority tail elements. Supply complete elements.
  Maps combine entries; the higher-priority value wins for a shared key.
- IOC environment names use uppercase underscores, for example
  `ioc.pipeline.failure-policy` → `IOC_PIPELINE_FAILURE_POLICY`. Names are
  checked against the IOC schema, not accepted by a generic spelling guess.
  Prefer complete YAML lists to indexed environment overrides.

### Failed checks

| Output | Action |
|---|---|
| `CONFIG.YAML_UNREADABLE` / `CONFIG.SEMANTIC_UNREADABLE` | Check the path, file type and service-readable staged input. |
| `CONFIG.YAML_INVALID` | Fix YAML syntax at the reported line/column; source text is not echoed. |
| `CONFIG.UNKNOWN_PROPERTY` and applicable `CONFIG.LEGACY_*` | Correct or migrate the named setting in every declaring source, including shadowed ones. |
| `CONFIG.BINDING_FAILED` | Use the documented type and accepted selector tokens at the reported field. |
| `CONFIG.VALIDATION_FAILED` | Resolve the reported field/cross-section constraints; later checks may not have run. |
| `CONFIG.REGISTRY` | Check registered names, artifact references, write policies and named processing plans. |
| `CONFIG.SEMANTIC_USAGE` / `CONFIG.SEMANTIC_SOURCE_OVERRIDE` | Use the helper's supported invocation; do not extend its configuration sources with beans. |
| `CONFIG.CHECK_FAILED` | Investigate incomplete checking, including missing required imports; this is not acceptance or a proven value error. |

Checker failures exit `78`; the shell helper can also fail for staging, systemd,
restart or health reasons. Preserve the output and inspect the failed phase.
Successful preflight does not substitute for checking service health after apply.
The [internal report contract](../dev/configuration-failure-reports.md) details
category, location and disclosure limits.

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

## IOC processing plans

Document preparation uses a private disk workspace, including streamed source
text and occurrences. Physical budgets do not change canonical receipt policy
identity. These are admission/cache/row/disk limits, not a bound on total JVM
heap, parser allocations or native memory. See the
[resource-sizing guide](bulk-resource-sizing.md) for measured scope and limits.

| Property | Default | Meaning |
| --- | --- | --- |
| `ioc.processing.workspace.directory` | `./var/document-preparation` | Private owned root; keep it on local storage. |
| `ioc.processing.workspace.memory-bytes` | `67108864` | Factory-wide cache/row admission budget; shared across artifacts and admitted preparations, not a heap limit. |
| `ioc.processing.workspace.cache-kib` | `4096` | SQLite page-cache target per live workspace (at least 64 KiB); mmap is disabled. |
| `ioc.processing.workspace.maximum-row-bytes` | `262144` | Maximum encoded prepared row; at most 16777216 bytes and at least the field limit. The same numeric value also bounds HTML parser region characters; oversized regions fail preparation rather than being truncated. |
| `ioc.processing.workspace.maximum-field-bytes` | `65536` | Maximum UTF-8 field size. |
| `ioc.processing.workspace.workspace-bytes` | `2147483648` | Per-invocation source/database/journal disk quota. |
| `ioc.processing.workspace.total-disk-bytes` | `8589934592` | Root-wide disk admission quota, including retained pins. |
| `ioc.processing.workspace.batch-rows` | `128` | Maximum append group between commits/quota checks (1..4096). |
| `ioc.processing.workspace.retention` | `24h` | Expired inactive pins are pruned on the next admission; live leases are protected. |

Budgets must be consistent: the field limit cannot exceed the row limit, the
root-wide disk allowance must cover one workspace, and memory must cover the
validated cache/working-row allowance. Retention must be positive. Source
admission must also fit within one quarter of the workspace disk budget; see
`ioc.ingestion.execution.max-source-bytes` below. Increasing a physical quota
does not qualify a larger workload without measuring the complete service.

The `ioc.processing` tree includes a required `document-plan`. Named plans are
checked at startup and execute when selected by a document or processed-import
contract. The shipped `original-document` retains full original IOC values;
missing selections fail admission. A policy change affects new observations;
existing canonical rows are not rewritten. In daemon mode, changing a selected
document policy requires all unfinished document admissions and processing files
to drain under the previous policy before the service starts with the new one.
For a complete activation example and drain procedure, see the
[IOC processing routes guide](ioc-processing-routes.md).
An unchanged policy can recover its own unfinished work. A pinned import delivery
with no sealed stage blocks recovery if its old contract is unavailable; restore
the old configuration to finish it. The document gate uses the complete processing
fingerprint, so a change to another declared plan can also require a drain.
Run only one active daemon per service database during a policy change. Committed
imports finalize from receipts.

- `ioc.dataframe-import.contracts[].processed-route.plan` selects a named IOC
  plan for one `processed` contract. This binding is required even when intake is
  disabled. `as-is` contracts cannot select a route.
  `ioc.dataframe-import.contracts[].processed-route.inputs` is an ordered list
  of source cells; each `ioc.dataframe-import.contracts[].processed-route.inputs[].artifact`
  and `ioc.dataframe-import.contracts[].processed-route.inputs[].target` must
  name a mapped contract cell. `ioc.dataframe-import.contracts[].processed-route.outputs`
  lists the authorized final fields. Each
  `ioc.dataframe-import.contracts[].processed-route.outputs[].artifact` must
  match exactly one route branch, and
  `ioc.dataframe-import.contracts[].processed-route.outputs[].targets` names
  mapped target fields. Source authority fields cannot be replaced.

- `ioc.processing.document-plan` selects one entry in `ioc.processing.plans` by
  `ioc.processing.plans[].name`. A selected document plan must route each
  enabled artifact or list it in `ioc.processing.plans[].omitted-artifacts`.
- `ioc.processing.plans[].observation-selection` accepts `retained-observations`
  or `final-key` (the default for a newly declared plan). The shipped document
  plan uses `retained-observations`: KEEP_FIRST preserves source-level selection,
  mapped-collision multiplicity and ID/provenance accounting; LAST_NONEMPTY still
  examines every occurrence. `final-key` selects whole-row winners per artifact
  after routing, including derived host/IP collapse.
- `ioc.processing.plans[].views` declares unary derived values. Each entry has
  `ioc.processing.plans[].views[].name`,
  `ioc.processing.plans[].views[].operation` and
  `ioc.processing.plans[].views[].input`. `network.host` derives a bare host;
  `network.without-scheme` removes only HTTP(S), preserving host spelling, port,
  path, query and fragment. Both validate the entire network address and retain
  source attribution. The scheme-free result is URL when it has address details,
  otherwise DOMAIN or IPV4. Operations take no arguments.
  `view.recover` requires
  `ioc.processing.plans[].views[].arguments.on-reasons` and
  `ioc.processing.plans[].views[].arguments.use-view`. Recovery creates a
  separate view and accepts only `empty`, `unsupported-scheme`,
  `invalid-authority`, `invalid-host`, `invalid-port` or
  `unsupported-address-form` as input-dependent reasons.
- `ioc.processing.plans[].classifications` binds
  `ioc.processing.plans[].classifications[].view` to
  `ioc.processing.plans[].classifications[].policy`. The only policy is
  `configured`, which uses the existing `ioc.classify.rules`.
- `ioc.processing.plans[].routing.mode` accepts `first`, `all` or `exclusive`.
  `ioc.processing.plans[].routing.on-unmatched.action` is `skip`, `reject` or
  `route`; `ioc.processing.plans[].routing.on-unmatched.branch` names the
  default branch only for `route`.
- `ioc.processing.plans[].routing.branches` is ordered. Each entry has
  `ioc.processing.plans[].routing.branches[].id`,
  `ioc.processing.plans[].routing.branches[].artifact` and
  `ioc.processing.plans[].routing.branches[].default-view`.
  `ioc.processing.plans[].routing.branches[].field-views` maps an existing
  output column to a declared view. Source and ID columns cannot be overridden.
- The optional leaf condition uses
  `ioc.processing.plans[].routing.branches[].eligibility.on` and
  `ioc.processing.plans[].routing.branches[].eligibility.predicate`.
  `type-in` requires enum values in
  `ioc.processing.plans[].routing.branches[].eligibility.arguments.types`;
  feature predicates accept no arguments. Conditions have exactly one shape:
  `ioc.processing.plans[].routing.branches[].eligibility.all` with ordered
  `ioc.processing.plans[].routing.branches[].eligibility.all[]` children,
  `ioc.processing.plans[].routing.branches[].eligibility.any` with ordered
  `ioc.processing.plans[].routing.branches[].eligibility.any[]` children, or
  `ioc.processing.plans[].routing.branches[].eligibility.not` with one child.
- For no-match routing, `ioc.processing.plans[].routing.default-branch.id`,
  `ioc.processing.plans[].routing.default-branch.artifact`,
  `ioc.processing.plans[].routing.default-branch.default-view` and
  `ioc.processing.plans[].routing.default-branch.field-views` use the same
  bindings as an ordinary branch. A default must have no eligibility; the
  structurally bindable paths
  `ioc.processing.plans[].routing.default-branch.eligibility.on`,
  `ioc.processing.plans[].routing.default-branch.eligibility.predicate`,
  `ioc.processing.plans[].routing.default-branch.eligibility.arguments.types`,
  `ioc.processing.plans[].routing.default-branch.eligibility.all`,
  `ioc.processing.plans[].routing.default-branch.eligibility.all[]`,
  `ioc.processing.plans[].routing.default-branch.eligibility.any`,
  `ioc.processing.plans[].routing.default-branch.eligibility.any[]` and
  `ioc.processing.plans[].routing.default-branch.eligibility.not` are rejected
  by semantic admission.

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
| `ioc.sink.csv.null-literal` | non-blank string | `NULL` | Unquoted marker for absent cells; cannot contain delimiter, quote, CR or LF. |
| `ioc.sink.csv.record-separator` | `lf`, `crlf` | `crlf` | Explicit record endings. See [CSV output format](../dev/csv-output-format.md) for combinations and compatibility. |
| `ioc.sink.csv.header-quoting` | `all`, `minimal`, `none` | `all` | Quoting policy for column names, independent of data cells. |
| `ioc.sink.csv.value-quoting` | `all`, `minimal`, `none` | `all` | `all` quotes non-null values; `minimal` quotes only when needed; strict `none` rejects values requiring quoting or escaping. |
| `ioc.sink.csv.charset` | Java charset name | `UTF-8` | ALL/ALL projections diagnose charset replacement; MINIMAL/NONE and immutable slices require lossless encoding. |
| `ioc.sink.artifacts` | non-empty list | masks, ip_list, address_blacklist, hashes, ioc_aggregate | Override complete list elements, not isolated indexes. See the [IOC aggregate guide](ioc-aggregate.md) before first activation. |
| `ioc.sink.artifacts[].name` | unique string | required | Stable artifact identity referenced by export and row-key configuration. |
| `ioc.sink.artifacts[].enabled` | boolean | required | Disabled artifacts are not prepared or projected. |
| `ioc.sink.artifacts[].path` | path | required | Mutable projection file path; a filesystem root is invalid. Existing directories and access are checked at runtime. |
| `ioc.sink.artifacts[].accepts` | list of IOC types | required | Values: `IPV4`, `DOMAIN`, `URL`, `MD5`, `SHA1`, `SHA256`. |
| `ioc.sink.artifacts[].include` | list of filter names | optional | All configured include predicates must match. Also supports `is-bare-ip`. |
| `ioc.sink.artifacts[].exclude` | list of filter names | optional | A matching predicate excludes the row. Also supports `is-bare-ip`. |
| `ioc.sink.artifacts[].id` | object | optional | Required when a column uses the deferred `id` provider. |
| `ioc.sink.artifacts[].id.strategy` | `ascending`, `descending` | `ascending` in defaults | Canonical/mutable-projection ID allocation direction; immutable export slots have a separate identity contract. |
| `ioc.sink.artifacts[].id.start` | `auto` or signed 64-bit integer | `auto` | `auto` continues after the durable baseline. Failed reservations are not reused. |
| `ioc.sink.artifacts[].columns` | non-empty ordered list | artifact-specific | Defines CSV header order and canonical table columns. |
| `ioc.sink.artifacts[].columns[].name` | non-blank string | required | Output column name; unique within an artifact. |
| `ioc.sink.artifacts[].columns[].from` | provider name | required | `id`, `value`, `source.label`, `match.url`, `match.host`, `address.url`, `address.ip`, or `const`. |
| `ioc.sink.artifacts[].columns[].value` | string or null | optional | Literal used by `const`. |
| `ioc.sink.artifacts[].columns[].type` | `TEXT`, `INTEGER`, `REAL`, `BLOB`, `NUMERIC` | inferred/text | SQLite affinity, mainly for constant columns. |
| `ioc.sink.artifacts[].columns[].when-type` | IOC type | optional | Emits the provider only for the selected IOC type. |
| `ioc.sink.artifacts[].columns[].when-types` | non-empty list of IOC types | optional | Emits for any listed type; cannot be combined with `when-type`. List order has no effect. |
| `ioc.sink.artifacts[].columns[].when` | list of filter names | optional | All conditions must match. `is-clean-host` selects a bare domain; `is-address-with-detail` selects an address with a scheme, path, query, or port. Conditions are evaluated after classification and can be combined with a type gate. |
| `ioc.sink.artifacts[].columns[].transform` | ordered list | optional | Registered keys are `lower`, `lower-host`, `upper`, and `strip-prefix`; the parameterized form is `strip-prefix:<text>`. Not allowed on deferred `id`. |
| `ioc.sink.artifacts[].write-policy` | object | optional | Per-artifact duplicate selection and field mutation; absent preserves keep-first behavior. |
| `ioc.sink.artifacts[].write-policy.duplicate-selection` | `keep-first`, `last-nonempty` | `keep-first` when absent | `last-nonempty` selects one whole mapped occurrence with a nonblank selection-column value. |
| `ioc.sink.artifacts[].write-policy.selection-column` | output column | optional | Required for `last-nonempty`; cannot be an identity or ID column. |
| `ioc.sink.artifacts[].write-policy.fields` | list | optional | Non-key output fields with an ordered update policy. |
| `ioc.sink.artifacts[].write-policy.fields[].name` | output column | required within field rule | Cannot be an identity or ID column. |
| `ioc.sink.artifacts[].write-policy.fields[].update` | `latest-registered` | required within field rule | Uses durable delivery registration, independent of worker completion. |
| `ioc.sink.artifacts[].write-policy.fields[].empty` | `keep-existing` | required within field rule | Blank input preserves stored value and its origin. |

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

During the v0.2.0 rollback overlap, the 0.3.0 binary accepts only the four exact
built-in identity entries from the v0.2.0 packaged template without
`record-key`, `match-keys`, or `epoch`. It resolves them to the shipped current
definitions and logs `CONFIG.LEGACY_ARTIFACT_IDENTITY`. A custom or modified
entry missing `record-key` fails semantic validation. While the shipped
aggregate sink is present, the complete four-entry legacy base also resolves
the shipped `ioc_aggregate` identity; partial or modified legacy definitions do
not receive this compatibility. Once v0.2.0 is no longer a rollback target,
merge all explicit current definitions from `application.yml.new` through
`ioc-config apply`; do not keep the compatibility shape as a template for new
artifacts. This adapter is release-transition code, not a permanent alternate
schema: it is removed once direct v0.2.0 upgrade/rollback is no longer supported.

## Managed dataframe import

Managed dataframe import is disabled in both shipped configurations. Enabling it
starts intake only after the shared startup recovery barrier has reconciled
ordinary ingestion and managed import. Keep `enabled: false` until the source,
authority and contract catalog has been qualified for the deployment.
An SMB source also requires the operator-provisioned private namespace and
two-identity permission checks described in the
[managed-import guide](dataframe-import.md#submit-an-smb-delivery). No additional
configuration key creates or audits that namespace.

The production template uses an event-first low-latency preset: import full
listing every `2s`, a `2s` stability window and `2s` retry delay; ordinary local
ingest scans every `5s` with a `2s` quiet period; export coalesces changes for
`1s` with a `10s` recovery poll and `30s` maximum cap; SMB notifications use a
`1s` debounce and sync reconciliation uses `10s`. These are template overrides,
not the built-in defaults shown below. Do not reduce a quiet period below the
longest producer-side non-atomic copy interval.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.dataframe-import.enabled` | boolean | `false` | Runtime intake switch; declared catalogs/plans are still validated when disabled. Startup stays fail-closed until recovery completes. |
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
| `ioc.dataframe-import.sources[].location` | transport-relative path | required | Dedicated managed-import location. For SMB, pre-create `.ioc-managed-import/{processing,terminal,quarantine,probe}` below it; the runtime never creates these directories. |
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
| `ioc.dataframe-import.contracts[].duplicate-policy` | `coalesce`, `keep-first`, `last-nonempty` | required | Deterministic within-delivery duplicate handling; `last-nonempty` keeps the last logical CSV record with a nonblank selection value. |
| `ioc.dataframe-import.contracts[].duplicate-selection-column` | recognized header | required for `last-nonempty` | Column used only to select the winning whole record; it does not merge cells across duplicate rows. |
| `ioc.dataframe-import.contracts[].renew-unchanged` | boolean | required | Whether an accepted exact no-op confirms lifecycle validity. |
| `ioc.dataframe-import.contracts[].formula-policy` | `reject`, `machine-only-preserve` | required | Preservation requires explicit source authority. |
| `ioc.dataframe-import.contracts[].merge-default` | `keep-existing`, `fill-missing`, `replace-non-null`, `authoritative`, `reject-conflict` | required | Column/artifact overrides cannot exceed the source ceiling. |
| `ioc.dataframe-import.contracts[].artifacts` | mapping list | required | Exactly one `primary`; related entries require related routing. |
| `ioc.dataframe-import.contracts[].artifacts[].name` | artifact name | required | References a configured sink/identity artifact. |
| `ioc.dataframe-import.contracts[].artifacts[].role` | `primary`, `related` | required | Exactly one primary mapping per contract. |
| `ioc.dataframe-import.contracts[].artifacts[].record-key` | identity definition ID | required | Must equal the artifact's active declared record key. |
| `ioc.dataframe-import.contracts[].artifacts[].match-keys` | identity definition ID list | required | Every name must exist for that artifact. |
| `ioc.dataframe-import.contracts[].artifacts[].merge-default` | merge policy | optional | Artifact override below the source ceiling. |
| `ioc.dataframe-import.contracts[].artifacts[].source-label-target` | target column | optional | Receives the configured managed-source label for processed imports; the target must be mapped by this branch. |
| `ioc.dataframe-import.contracts[].artifacts[].exactly-one-nonempty` | target-column list | empty | Requires exactly one listed carrier after mapping; used to reject ambiguous or all-empty IOC aggregate rows. |
| `ioc.dataframe-import.contracts[].artifacts[].columns` | mapping list | required | Target/source and ordered registered transforms; arbitrary code or SQL is not allowed. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].target` | artifact column | required | Unique target within the artifact branch. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].source` | recognized header | required | Required or optional canonical input header. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].transforms` | ordered transform list | empty | Registered transform specifications only. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].merge-policy` | merge policy | optional | Column override below the source ceiling. |
| `ioc.dataframe-import.contracts[].artifacts[].columns[].validation` | registered validator | optional | After transforms: `bare-ip`, `url-address`, `clean-domain`, `network-address`, `hash`, `md5`, `sha1`, `sha256`, or `canonical-integer`. Checks values without rewriting cells; NULL/ABSENT bypass value validation. |
| `ioc.dataframe-import.contracts[].requested-slot` | optional mapping | omitted | Only artifacts with an external ID and a containing stable-slot export profile may use it. |
| `ioc.dataframe-import.contracts[].requested-slot.source-column` | recognized header | required when present | Positive requested external slot, never canonical identity. |
| `ioc.dataframe-import.contracts[].requested-slot.profile` | export profile | required when present | Scopes the external slot. |
| `ioc.dataframe-import.contracts[].requested-slot.existing-record-policy` | `preserve-existing`, `reject-mismatch` | required when present | Handles a survivor whose stable slot differs from the request. |

## Immutable export

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.export.enabled` | boolean | `true` | Controls manual and daemon export availability. |
| `ioc.export.root` | path | `./var/export` | Holds immutable slices and temporary export staging; durable coordination is in the service database. |
| `ioc.export.trigger.type` | `interval`, `quiet-period` | `interval` | `quiet-period` coalesces changes; periodic checks remain the recovery backstop. |
| `ioc.export.trigger.interval` | positive duration | `5m` | Scheduler/reconcile cadence. |
| `ioc.export.trigger.quiet-period` | positive duration | `5m` | Delay after the latest canonical change before export. |
| `ioc.export.trigger.max-cap` | positive duration | `1h` | Maximum postponement under continuous ingestion. |
| `ioc.export.profiles` | non-empty list | reputation-lists, address-blacklist, ioc-aggregate | Ordered, indivisible artifact sets. |
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
| `ioc.sync.endpoints[].smb.host` | hostname or IP | required | SMB server; do not include `smb://` or append a port. |
| `ioc.sync.endpoints[].smb.port` | integer `1..65535` | `445` | TCP port used by regular and `CHANGE_NOTIFY` SMB sessions. Override only when the server or an explicit port-forward listens outside standard TCP/445. |
| `ioc.sync.endpoints[].smb.share` | share name | required | Top-level share, without a path. |
| `ioc.sync.endpoints[].smb.domain` | string or null | optional | AD/NTLM domain; omit for local/workgroup accounts. |
| `ioc.sync.endpoints[].smb.username` | string | required | Prefer `${SMB_USER}`. |
| `ioc.sync.endpoints[].smb.password` | string | required | Use `${SMB_PASSWORD}`; never commit plaintext. |
| `ioc.sync.endpoints[].smb.encryption` | `required`, `preferred`, `disabled` | `required` | `required` allows only SMB3 and rejects a session unless encryption is effective; `preferred` explicitly allows an unencrypted fallback. Use this key for every fresh configuration. |
| `ioc.sync.endpoints[].smb.encrypt` | deprecated boolean alias | absent | Temporary rollback bridge: `true` maps to `required`, `false` to `disabled`, and startup emits a value-free warning. Do not set it together with `encryption`; migrate after the old rollback release is retired. |
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
| `ioc.ingestion.retry.max-attempts` | positive integer | `3` | Verified-key failures become rejected after exhaustion; pre-hash failures retain blocked ownership for investigation. |
| `ioc.ingestion.retry.backoff` | positive duration | `5s` | Delay between local ingestion retries. |
| `ioc.ingestion.ledger.type` | `file`, `jdbc` | `file` | Use `jdbc` when durable run-ledger integration is required; follow the deployment baseline. |
| `ioc.ingestion.ledger.path` | path | `./var/ledger` | Filesystem ledger location when type is `file`. |
| `ioc.ingestion.concurrency` | integer, exactly `1` | `1` | Single detection/claim path. Bounded parallel preparation is configured separately below; canonical promotion stays serial. |

Document execution admission bounds queued, running and ready preparations:

| Property | Built-in default | Constraint / meaning |
|---|---|---|
| `ioc.ingestion.execution.preparation-workers` | `2` | 1–2 preparation workers; canonical promotion remains serial in durable admission order. |
| `ioc.ingestion.execution.window` | `4` | Must satisfy workers ≤ window ≤ pending-document limit. |
| `ioc.ingestion.execution.max-pending-documents` | `64` | At most `256`; saturated input remains in the inbox for a later complete listing. |
| `ioc.ingestion.execution.max-pending-source-bytes` | `4294967296` | Positive total pending source-byte allowance; at least the per-source limit. |
| `ioc.ingestion.execution.max-source-bytes` | `536870912` | Positive per-source limit; no more than `ioc.processing.workspace.workspace-bytes / 4`. |

Preparation and claim sealing enforce the admitted byte allowance during copying. The `dataProcessingCapacity` health view reports saturation, blocked ownership and writer/readers. Exhausted pre-hash work remains owned in processing; preserve its token, journal and databases for investigation rather than moving it back into intake. Service schema v14 requires a stopped-service backup of both databases and owned files before upgrade; rollback requires restoring that backup.

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
`failed`. Immutable slices have separate age/count policy, but their daemon
reaper shares the maintenance interval/initial-delay and runs even when leaf
retention is disabled. Managed-import terminal units and lifecycle history have
separate retention mechanisms; do not sweep their owned directories as leaf files.

| Property | Type / accepted values | Built-in default | Guidance |
|---|---|---|---|
| `ioc.maintenance.retention.enabled` | boolean | `false` | Enables leaf-file targets only. Define backup/investigation requirements before enabling; immutable-slice reaping is independent. |
| `ioc.maintenance.retention.interval` | positive duration | `1h` | Shared daemon cadence for immutable-slice and enabled leaf-file reapers. |
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
| `spring.main.web-application-type` | `none`; daemon forces `servlet` | Bootstrap-owned runtime setting; do not override in installed YAML. |
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

`<prefix>/etc/ioc-extractor.env` is loaded by systemd for the daemon,
`ioc-config` and installed `ioc` wrapper. Use systemd EnvironmentFile syntax,
not a shell script; do not load it with `source`. The installer protects it as
`0640`, readable by root and the service group.

| Variable | Purpose |
|---|---|
| `JAVA_OPTS` | JVM options used by the daemon and configuration checker; keep secrets and application CLI arguments out. The installed oneshot `ioc` wrapper uses its own fixed JVM options. |
| `SMB_USER` | Username referenced by `${SMB_USER}`. |
| `SMB_PASSWORD` | Password referenced by `${SMB_PASSWORD}`. |

The Java executable is selected when packaging/installing the service; adding
`JAVA_HOME` to this file does not switch the rendered executable. Review memory
options together with the unit's cgroup limits. Environment changes require a
service restart; then check `ioc health` and `journalctl -u ioc-extractor`.
Apply YAML through the candidate workflow above. See
[daemon operations](daemon-operations.md) for health-client options.

## Sources and maintenance

Defaults and shapes come from
[classpath YAML](../../bootstrap/ioc-app/src/main/resources/application.yml),
[the production template](../../packaging/templates/application.yml) and
[typed properties](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocProperties.java).
The [documentation contract](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java)
checks property-name/vocabulary coverage and template binding. Prose, defaults,
recommendations and target behavior require review. Update both guide languages
with an affected setting; [configuration mechanics](../dev/configuration.md)
links validation and effective-source tests.
