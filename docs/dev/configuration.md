# Configuration and startup boundary

[Русская версия](ru/configuration.md)

The `ioc.*` tree is a strict external application contract. Bootstrap binds it to
`IocProperties`, rejects unknown declarations and invalid combinations, then
assembles the runtime graph. Domain and application code receive their own
values and ports; they do not read Spring properties or the environment.

The [operator reference](../guides/configuration.md) owns parameter names,
defaults and application instructions. This document owns validation flow,
service/library responsibilities and the boundary between checking configuration
and starting work.

## Sources and precedence

For the normal supported sources, precedence increases from left to right:

```text
classpath application.yml < external YAML < environment < JVM system properties < CLI
```

Classpath configuration imports optional `./configs/application.yml`. The
installed unit adds `./etc/application.yml` relative to its installation prefix.
Boot also resolves imports, active-profile documents/profile-specific files and
`SPRING_APPLICATION_JSON`; the simple ordering above is not an exhaustive list
of Boot sources. JSON overrides ordinary environment/system properties, while
CLI arguments still win. Keep source-loading policy explicit when comparing a
checker with a running deployment.

Lists are replaced as a whole by the highest-precedence source defining that
list. An overlay containing only `ioc.refang.rules[0].from` loses the element's
default `to` and all lower-priority rules; validation rejects the incomplete
element. Supply every required field of every retained element. Maps combine
entries across sources, with higher-precedence values winning for the same key.
Do not use list-element behavior as a model for map overlays.

`IOC_*` is reserved for IOC settings. `IocEnvironmentPropertyMatcher` resolves
underscore-separated names against the declared record shape, including list
indexes. Unknown IOC names fail; ambiguous segmentations are not guessed.
IOC keeps its own spelling rules and converter fallback on top of the
[shared declaration inspector](configuration-inspection.md). The library's
broader name recognition does not expand the service's supported syntax.
Opaque/nonenumerable custom sources need explicit coverage review; simply
adding a new `PropertySource` does not prove that all its names were inspected.

After refresh and before runners, `IocConfigurationOverrideReporter` reports
`ioc.key <- source` once for the first external declaration of each name. It
does not read values. This is declaration provenance, not proof of the value
that Binder used: shadowed list elements and null declarations can still be
observable. Normal startup owns the report; the minimal checker does not scan
that component.

## Validation flow and ownership

```text
Boot ConfigData loading (YAML, imports, profiles, effective overrides)
  -> unknown-name preflight over prepared sources
  -> typed binding and conversion
  -> Bean Validation and collect-all IOC semantic validation
  -> registry references, artifact policies and named-plan admission
  -> runtime composition and state-dependent checks
  -> startup recovery / readiness / operational work
```

| Component | Responsibility |
|---|---|
| `IocProperties`, `IocProcessingProperties` and selector converters | External shape, type conversion, required values and local constraints |
| `IocUnknownConfigurationPreflight` | Unknown names in supported YAML, CLI, system-property and environment channels, including shadowed declarations |
| `IocConfigPreflight` | Cross-section constraints: lifecycle, execution budgets, artifact identity, CSV shape, sync references and import catalog |
| `ConfigRegistryPreflight` and owning catalogs | Registered providers/transforms/filters/predicates, write policies, named plans and processed-import plan bindings |
| `AppConfig` and other runtime configuration | Concrete adapters, export plans, storage migrations, durable-policy compatibility, recovery and schedulers |
| Controlled failure projection/analyzers | Selected categories, safe field locations and corrective guidance; see [failure reports](configuration-failure-reports.md) |

Constructors must not throw for ordinary operator mistakes that belong to
collect-all validation. Otherwise conversion can stop on the first error and
hide other violations. Collect-all applies within a reached validation phase;
a syntax/conversion failure cannot establish that later phases ran.

Disabled runtime switches do not make arbitrary declarations inert. Unknown
names and declared registry/plan references are still checked. A `processed`
import contract needs its named route even while intake is disabled. A malformed
prepared catalog must not become a deferred first-delivery failure.

## Configuration-only commands

`IocExtractorApplication` handles the internal packaging check paths before the
ordinary CLI and Spring runtime:

| Entry point | Scope and result |
|---|---|
| `--ioc.validate-yaml=<file>` / `IocYamlSyntaxCheck` | Read and parse one file only; `CONFIG.YAML_VALID` and exit `0` do not establish binding or semantic validity |
| `--ioc.validate-config=<file>` / `IocSemanticConfigurationCheck` | Syntax check, then a minimal configuration context; `CONFIG.SEMANTIC_VALID` and exit `0` establish the implemented configuration preflights |
| Installed `ioc-config check [candidate.yml]` | Stage service-readable YAML and invoke the semantic path as the service account; omitted candidate selects the installed YAML |

Checker failures return `78`. Usage/readability failures and incomplete checks
remain failures; the [report contract](configuration-failure-reports.md)
distinguishes their codes. A successful check does not open the application
SQLite stores, initialize transports or start intake/schedulers. It also does
not qualify filesystem permissions, remote endpoints, existing DB schemas,
durable lifecycle policy or recovery against actual runtime state. Checks owned
only by runtime composition, such as export-plan execution support, are not
silently included in `CONFIG.SEMANTIC_VALID`.

### Effective sources and context guard

The semantic checker explicitly loads classpath defaults and the candidate.
Normal allowed Boot imports, profiles and effective external overrides still
participate; an existing `./configs/application.yml` participates through the
classpath import. A missing required import fails; a missing optional import
may be accepted. This checks effective configuration, not an isolated file.

The checker rejects CLI controls for `spring.config.location`,
`spring.config.additional-location`, `spring.config.import` and
`spring.config.name`, including indexed/relaxed forms. It rejects
`spring.main.sources` declarations and indexed descendants across loaded
channels before additional sources can instantiate beans. Its own configuration
forces non-web, eager validation without keep-alive, shutdown-hook registration
or startup-info output. The context is closed after the check.

Imports are trusted deployment inputs; the minimal bean graph is not a path,
network or configuration-loader sandbox. Console suppression temporarily changes
process streams under the checker's monitor; arbitrary logging appenders are
outside that boundary. Use it as a short-lived command, not a shared embedded
runner. The [separate library recipe](../guides/libraries/config-spring-boot-check.md)
demonstrates a different source policy: explicit YAML and literal overrides
without ambient environment, ConfigData imports or profiles. The reusable
inspector imposes neither service's policy.

## Installed check, apply and startup

The installed `ioc-config` helper runs via systemd with the service account,
working directory, EnvironmentFile and JVM overrides. Its staged copy retains
a YAML suffix and service-readable ownership. `check` does not restart the
service. `apply` requires a separate regular non-symlink candidate, serializes
other applies, stages and validates the bytes, then atomically replaces live
YAML and restarts the service with a bounded health wait.

A failed precheck leaves live YAML and the service unchanged. A failed restart
or health wait preserves the rejected YAML, restores the previous file and
attempts a second restart/health check. Restored bytes do not prove that the old
service is healthy. This is configuration-file recovery: it does not undo
database migrations, lifecycle activation, admitted work or remote effects.
State-changing settings need the owning migration/backup procedure before apply.

The unit's `ExecCondition` repeats semantic checking before main activation.
Exit `78` skips activation without entering `Restart=on-failure`;
`RestartPreventExitStatus=78` is additional main-process protection. Local
deployment checks the staged new JAR with the service environment before
stopping the active service and entering backup/activation. These mechanisms
share validation but have different rollback scopes; [packaging](../../packaging/README.md)
owns those procedures.

There is no general hot reload. The supported operator flow applies YAML via
the candidate helper; environment-file changes require a restart. Systemd
EnvironmentFile syntax is not shell syntax and must not be loaded with `source`.

## Runtime modes and lazy initialization

`ioc.runtime.mode` selects `oneshot` or `daemon`; observability mode selects
logging and does not replace runtime mode. Ordinary lightweight help, version,
syntax errors and the HTTP health client can finish before Spring starts.
Those paths do not validate application configuration. Explicit daemon-mode
arguments select the runtime path rather than the ordinary early CLI shortcut.
Version output requires embedded build identity; missing metadata returns a
one-line error and exit `1` without an invented version.

Oneshot uses lazy runtime composition, while configuration preflights remain
eager. Daemon forces eager singleton initialization and the actuator web surface.
Migrations and coordinated recovery precede intake; a configuration-only pass
does not imply that this recovery barrier has passed. Concrete adapters remain
in `AppConfig`, `SyncConfig`, `EventCoordinationConfig` and owning bootstrap
configuration classes.

## State-sensitive settings

- **Lifecycle:** classpath defaults use `disabled`; the fresh production template
  uses `fixed/12h` with `existing-records: reject`. Existing installations retain
  their operator YAML. Destructive legacy-row cutover requires explicit `expire`;
  once durable activation has begun, returning that DB to `disabled` fails with
  `LIFECYCLE.POLICY_MISMATCH`. Zero is not a cleanup command. See
  [lifecycle mechanics](canonical-record-lifecycle.md) and its
  [operator procedure](../guides/canonical-record-lifecycle.md).
- **Managed import:** both shipped configurations disable intake. Classpath
  catalogs are empty; the production template already declares an aggregate
  authority/processed contract and a matching plan, but no sources. Enabling
  requires daemon mode, JDBC stores, fixed validity and a complete accepted
  source/authority/contract catalog. Catalog compilation produces one immutable
  result and behavior fingerprint, never a partially resolved runtime catalog.
  Import and ordinary ingest share the startup recovery barrier. SMB sources
  reuse endpoints even with ordinary sync disabled; polling remains authoritative,
  and private SMB directories/permissions are operator-provisioned. Snapshots
  remain local and transport-neutral. See [import mechanics](dataframe-import.md).
- **Processing and admission:** budget relationships are validated together;
  preparation workers and the bounded window do not make canonical promotion
  parallel. Changing a selected processing policy can block unfinished-work
  recovery; follow the [drain procedure](../guides/ioc-processing-routes.md).
- **Identity:** configured record/match keys and epoch name durable contracts.
  Editing YAML does not migrate populated canonical identity. During the supported
  v0.2.0 rollback overlap, `V020ArtifactIdentityCompatibility` accepts only the
  exact four legacy built-in tuples. The complete legacy base also resolves the
  shipped aggregate identity when its sink is present. Modified/custom near
  matches fail. `CONFIG.LEGACY_ARTIFACT_IDENTITY` is value-free; retire the
  compatibility adapter after the old binary leaves the supported rollback path.
- **Renames:** the bounded alias policy follows
  [ADR 0027](../ADR/0027-rollback-safe-configuration-evolution.md). The temporary
  typed `smb.encrypt` alias is absent from fresh templates and observed through
  `IocConfigurationMigrationCatalog`. Post-binding semantic validation rejects
  simultaneous old/new fields; Boolean conversion and retirement remain IOC
  policy. Removed `read-timeout` remains unknown and receives
  `CONFIG.LEGACY_SYNC_TIMEOUT`, rather than a restored tombstone field.

## Extension and verification

For a new setting, update the typed shape, defaults/omission semantics,
validation, metadata, full production template and paired operator reference.
Add binding and meaningful rejection/boundary cases. Selectors use typed tokens
and converters. Register a provider/transform/predicate in the owning catalog
used by both preflight and composition. New channels must preserve strict-name,
coverage and controlled-output contracts; do not bypass `Environment`.

## Sources of truth

- [Classpath configuration](../../bootstrap/ioc-app/src/main/resources/application.yml),
  [production template](../../packaging/templates/application.yml),
  [typed model](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocProperties.java)
  and [processing model](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocProcessingProperties.java).
- [Bootstrap source/reference](../../bootstrap/ioc-app/README.md):
  `ConfigPreflightConfiguration`, `IocUnknownConfigurationPreflight`,
  `IocConfigPreflight`, `ConfigRegistryPreflight`, `IocSemanticConfigurationCheck`,
  `IocConfigurationOverrideReporter`, `DaemonWebEnvironmentPostProcessor` and
  `DataframeImportConfiguration`.
- [Installed helper](../../packaging/templates/ioc-config) and
  [unit](../../packaging/templates/ioc-extractor.service).
- [Real ConfigData/channel/list tests](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocConfigurationContextIT.java),
  [binding tests](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocPropertiesBindingIT.java),
  [semantic checker tests](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocSemanticConfigurationCheckTest.java)
  and [documentation contract](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java).
  The last checks property/vocabulary coverage and template binding, not prose
  completeness, correct defaults or target operation.

## When to update this document

Update it when source selection, precedence, list/map behavior, validation
ownership, checker guarantees, alias rules or runtime/recovery boundaries change.

## Related documents

- [Operator configuration](../guides/configuration.md),
  [controlled failure reports](configuration-failure-reports.md) and
  [declaration inspection](configuration-inspection.md).
- [Processing](processing.md), [storage](storage.md),
  [observability](observability.md) and
  [ADR 0016](../ADR/0016-config-preflight-strict-binding.md).
