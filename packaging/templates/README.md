# Host templates

## Purpose and sources

Installed host configuration and entry points rendered by the packaging scripts.
These files are inputs, not generated output to edit after installation. The
operator YAML/env remain external state; helpers/unit are refreshed from templates.

| Template | Installed responsibility |
|---|---|
| [application.yml](application.yml) | Production daemon override of classpath defaults |
| [ioc-extractor.env](ioc-extractor.env) | systemd environment and daemon JVM options; secret placeholders |
| [ioc-extractor.service](ioc-extractor.service) | Main daemon mode, configuration condition, restart, sandbox and resource controls |
| [ioc](ioc) | Root-invoked oneshot CLI running as the service user through a transient unit |
| [ioc-config](ioc-config) | Root-invoked configuration-only check and YAML apply/restore |

`@PREFIX@`, `@JAVA_BIN@`, `@USER@` and `@GROUP@` are rendered into relevant
unit/helpers. The daemon unit additionally uses `@SERVER_PORT_ARG@`, while the
config helper uses `@SERVER_PORT@`. The scripts do not substitute these tokens in
YAML/env; Spring resolves credential placeholders at runtime. Installation must
leave no unresolved host-rendering tokens in executable output.

## YAML and environment ownership

Relative YAML paths resolve under the prefix, used as the working directory.
The template selects daemon mode, loopback health, JDBC truth and complete named
processing plans. Fresh lifecycle is fixed `12h`, with `30d` history/receipt
retention and rejection of unexpected existing rows. The built-in managed-import
contract catalog is an inactive example until import/sources are admitted.
Sync and source-file maintenance retention are also disabled. Complete polling
and reconcile remain correctness backstops; event notifications provide latency.

Do not interpret a commented example as an enabled or target-qualified capability.
The [configuration guide](../../docs/guides/configuration.md) owns keys/list replacement,
[routes guide](../../docs/guides/ioc-processing-routes.md) owns named plan configuration,
and [deployment](../../docs/guides/deployment.md) owns reconciliation/activation.
An upgrade writes differing packaged YAML/env as `.new`, preserving the live files.

The env file is systemd `EnvironmentFile` syntax, not a shell program. Do not
`source` it to reproduce service configuration. Systemd expands `$JAVA_OPTS` for
the daemon; current defaults set a 512 MiB max heap and UTC/UTF-8. Credentials must
remain environment values rather than arguments or plaintext YAML. A changed env
file needs operator handling; `ioc-config apply` manages only YAML.

## Main unit and CLI differences

The daemon unit validates config in `ExecCondition`, forces daemon and servlet
mode, and optionally pins `server.port` on its command line. Config validation uses
small explicit heap flags after `JAVA_OPTS`; normal runtime uses daemon JVM options.
`Restart=on-failure`, config exit prevention and rate limiting govern restarts;
shutdown has a 60-second systemd bound. Condition failure and runtime failure are
different outcomes and must be inspected separately.

The main unit uses a non-login account, empty capability sets, `ProtectSystem=strict`,
`ProtectHome`, private temp/devices and restricted address families. Only prefix
`var` and `dataframe` are writable. Current resource controls include `MemoryHigh=768M`,
`MemoryMax=1G`, CPU quota 200% and a 512-task limit. Heap is only part of that memory
budget. Custom data paths/JDK locations must fit the sandbox and target native
loader requirements; tuning YAML alone does not extend systemd write permissions.

The `ioc` launcher reads the same env file and external YAML but starts a separate
oneshot process with fixed JVM flags, not daemon `JAVA_OPTS`. It sets oneshot
runtime/observability/profile and no web application. It does not inherit a daemon
unit's CLI port override; pass the intended port to `health`. Review commands that
mutate shared state before using them beside an active daemon. The transient CLI
has its own sandbox declarations, not every resource control of the main unit.

## Configuration helper and failure boundary

`check [candidate.yml]` rejects symlinks, stages root-owned/group-readable YAML
with a real `.yml` suffix, and runs the active JAR's configuration-only validator as
the service account with the installed environment. It does not compose runtime
adapters, open SQLite or probe remote capabilities. Temporary candidate staging is
still local filesystem work, even though application validation is state-free.

`apply candidate.yml` requires a separate file, holds the config-apply lock,
validates staged bytes, saves the previous YAML, atomically replaces it and restarts.
Its gate checks service activity plus aggregate health `UP` (40 attempts at one-second
intervals plus request time); install/deploy instead check three local storage
components. Optional component degradation can therefore reject a YAML apply even
when deployment storage checks would pass.

On that failed restart/health path it retains a rejected candidate, restores YAML
and attempts to restart the old configuration. This is a YAML rollback, not a DB,
binary, env, helper or external-effect rollback. The helper's EXIT cleanup removes
staged files; it is not an all-failure transaction. Preserve a coordinated recovery
point before a candidate that can cause irreversible startup effects.

## Verification

[Packaging contract tests](../tests/README.md) render unit/helper tokens
and inspect key safety declarations. [ConfigurationDocumentationContractTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java)
checks template binding/semantics/registries. These checks do not qualify a real
systemd host. The [parent reference](../README.md) owns lifecycle scripts;
operator procedures and troubleshooting belong to the linked guides.
