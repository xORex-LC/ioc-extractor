# Developer tools

## Purpose and structure

The root [Makefile](../../Makefile) is the supported local facade. This directory
contains GNU/Linux helpers around the bootable application, Maven wrapper and
synthetic fixtures. It is outside the reactor and does not own application policy
or production [deployment](../../packaging/README.md).

| Source | Responsibility |
|---|---|
| [common.sh](common.sh) | Shared path/ownership checks, JAR lookup, process/health probes, colors and worktree fingerprint |
| [context.sh](context.sh) | Stable `key=value` version/Git/runtime and verify/PMD context |
| [doctor.sh](doctor.sh) | `core`, `dev`, `ci`, `security` or combined prerequisite checks |
| [bootstrap.sh](bootstrap.sh) | Explicit pinned, checksum-verified local lychee download |
| [app.sh](app.sh) | Invoke public CLI with the bootable JAR, optionally in a workspace |
| [runtime.sh](runtime.sh) | Start/stop/status/reset of a developer daemon |
| [submit.sh](submit.sh) | Stage a source then rename it into the developer document inbox |
| [database.sh](database.sh) | `sqlite3 -readonly` shell, schema or tables for either owned database |
| [logs.sh](logs.sh) | Raw or jq-based ECS queries by level, action, run and diagnostic |
| [fixture.sh](fixture.sh), [GenerateIocFixture.java](GenerateIocFixture.java) | Deterministic JDK-source-launched HTML/text input and manifest |
| [smoke.sh](smoke.sh) | `cli`, `oneshot`, `daemon`, local managed `import`, or `all` scenarios |
| [lifecycle-smoke.sh](lifecycle-smoke.sh) | Intended expiry/history/projection/export/ID reference workload; current TOOL-10 limitation below |
| [dataframe-import-load.sh](dataframe-import-load.sh) | Opt-in Native JDBC managed-import test workload and metrics |
| [ioc-aggregate-load.sh](ioc-aggregate-load.sh) | Duplicate-heavy daemon aggregate workload with optional baseline comparison |
| [release-notes-context.sh](release-notes-context.sh) | Read-only Git inventory and optional authenticated GitHub PR reads |

## Environment and isolation contract

Java 21+, Bash, GNU coreutils/findutils and Linux `/proc` are required. `doctor-core`
checks Java's major version, Git, Make and the wrapper; it does not prove compiler,
`javadoc` or all scenario dependencies. `doctor-dev` requires curl and reports jq
and sqlite3 as optional inspection tools; scenarios that need them require them
explicitly. Import load additionally uses `/usr/bin/time`, `tee` and host inventory tools. Use `make help` and the relevant script's usage
for parameters; `app.sh --help` is application help and starts the Java launcher.

Validated workspaces must be normalized, use supported path characters and stay
strictly below repo-local `.dev/` without symlink traversal. Defaults include
`.dev/runtime`, `.dev/oneshot`, `.dev/fixtures` and per-scenario workspaces. Reset
removes the entire selected workspace after stopping its recorded daemon. Smoke
and lifecycle scenarios reset their own fixed workspace before running; aggregate
load resets baseline/candidate sub-workspaces. Preserve needed evidence first.

The CLI helper without `--workspace` runs from the checkout root; fixture
`--output`/`--manifest` can deliberately write elsewhere. Workspace validation
is not a sandbox for application configuration: explicit path/transport overrides
still take effect. Use synthetic inputs and private storage, and keep credentials
out of arguments and captured logs. Application launchers unset ambient `DEBUG`
and `TRACE`; runtime options remain explicit.

## Entry points and runtime ownership

```bash
make context
make doctor-core
make help
make fixture SIZE=1000 SEED=42
```

From the repository root, this sequence uses the isolated defaults:

```bash
make runtime-up
make submit SOURCE=.dev/fixtures/ioc-1000-seed-42.html
make runtime-status
make logs-errors
make db DB=dataframe DB_COMMAND=tables
make runtime-down
```

The runtime defaults to loopback port 18081 and launches the daemon with `nohup`,
not systemd. It records PID, JAR and port under `run/`; stop checks `/proc` for the
recorded JAR argument before signaling. SIGTERM gets a bounded wait before
SIGKILL. This is a developer ownership guard, not production supervision or
cross-process fencing. `status` returns nonzero for stopped/unhealthy state.

Startup readiness checks `jdbcStorage`, `dataframeStorage` and `artifactStorage`
health components; it does not by itself qualify every optional subsystem.
Repeatable `--set KEY=VALUE` accepts indexed properties. Repeatable `--jvm-arg`
is limited to supported memory and boolean JVM options; values such as `-jar`
are rejected. Configuration semantics remain the application's responsibility.
An explicit JAR must be a regular nonsymlink file; auto-discovery requires exactly
one bootable `ioc-app-*.jar` under its module `target/`.

`submit.sh` accepts regular nonsymlink `.htm`, `.html` and `.docx` sources,
requires an existing inbox and rejects an already present destination. It copies
to a `.part` staging name then renames within the inbox, cleaning staging on exit.
That is source delivery, not ingestion acceptance or canonical commit; it is not
a concurrent multi-producer no-clobber protocol. Managed CSV import uses its own
inbox and flow, exercised by the separate import smoke.

`database.sh` opens existing owned DB files read-only. `logs.sh` defaults to the
workspace ECS file and understands nested JSON paths; `--file` selects an explicit
regular log and `--follow` streams with `tail -F`. `raw` needs no jq. Queries do not
change storage. Missing evidence is state in `context.sh`, which also invokes
Maven to resolve the effective version; it is not a purely Git-only command.
[CI tooling](../ci/README.md) owns receipt formats and freshness limitations.

## Fixtures and evidence meaning

The standalone Java generator needs no project dependencies. A fixed seed and
options produce deterministic input bytes with synthetic IOC values and section
markers. `SIZE` counts IOC-bearing input rows; the manifest separately records
unique input values, duplicates, type counts, defanging and SHA-256. These are
input facts, not canonical per-artifact or exported row counts. Text fixtures
serve oneshot paths; document inbox submission requires HTML/Word extensions.
Existing outputs require `--force`; fixture and manifest publication are separate
moves, not one transaction. Defaults stay under `.dev/fixtures`.

| Scenario | Evidence and practical limits |
|---|---|
| `make smoke` / `make smoke-cli`, `make smoke-oneshot`, `make smoke-daemon` | Public help/identity, oneshot SQLite/projection/export, daemon terminal/health; `all` also includes local CSV import |
| `make dataframe-import-smoke` | Local managed import terminal source/report, canonical result and projection; no SMB qualification |
| `make lifecycle-smoke`, `make lifecycle-load` | Intended active/expiry/history retention and allocator checks; currently blocked by obsolete terminal-name wait ([TOOL-10](../../docs/KNOWN-ISSUES.md#tool-10)) |
| `make dataframe-import-load-100k`, `make dataframe-import-load-1m` | Opt-in `JdbcManagedImportLoadProfileIT` with temporary Native JDBC data; reports under a timestamped `.dev/dataframe-import-load/` workspace |
| `make ioc-aggregate-load` | Same generated document through public daemon intake; candidate fields/origins, timing/RSS/query plans and optional pre-feature JAR comparison |

Lifecycle load selects at least 100k canonical rows and a deadline-wave/throughput
reference envelope. The launcher uses Xms128m/Xmx512m and the harness checks
VmHWM against a 1-GiB ceiling; it does not install a systemd/cgroup memory limit.
Its SQL allocator checks concern canonical IDs, not the reusable immutable-export
slots. TOOL-10 prevents treating the current harness as completed expiry/retention
or non-reuse evidence; focused JUnit and a help/syntax pass do not close it.

Import load caps input at 1m rows. The mixed profile requires divisibility by four,
seeds 3/4 SIZE active rows, then supplies equal insert/update/no-op/conflict shares.
It runs one opt-in Failsafe selector with upstreams, checks internal stage/promotion
and heap metrics, and retains Maven log, process time and report. The ordinary
verify skips that workload. Process RSS from `/usr/bin/time` and in-test heap
measure different scopes; neither is a service-wide capacity guarantee.

Aggregate load uses Xms128m/Xmx512m, samples candidate VmHWM and compares elapsed
time only when `BASELINE_JAR` is supplied. Matching input alone does not establish
comparable host/JDK/configuration; threshold changes require reviewed evidence.
Scenario reports are distinct from deterministic Maven tests and live external
transport qualification. Historical measurements and scope are in
[native capacity](../../docs/dev/native-capacity.md); obsolete comparison/Staged
drivers are not supported commands.

`make release-notes-context PREVIOUS_TAG=v0.2.0` emits Markdown to stdout after
checking tag syntax, resolution and ancestry. `GITHUB=1` adds authenticated `gh`
reads; no tag, release or notes file is written. Curate its inventory into impact,
upgrade actions and limitations rather than publishing the raw output.
