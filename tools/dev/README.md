# Developer tools

The root [Makefile](../../Makefile) is the supported local facade. These tools
resolve the repository from their own location and keep runtime state under
ignored `.dev/` workspaces. They do not write business fixtures directly into
production SQLite or replace deployment procedures.

## Supported commands

| Tool | Purpose |
|---|---|
| `bootstrap.sh lychee` | Install pinned, checksum-verified documentation checker locally |
| `context.sh` | Version/Git/runtime context and verify/PMD freshness |
| `doctor.sh` | Check core/developer/CI/security prerequisites |
| `app.sh` | Public CLI using the bootable application JAR, optionally isolated |
| `fixture.sh`, `GenerateIocFixture.java` | Deterministic text/HTML fixtures and manifests |
| `runtime.sh` | Start, stop, inspect or reset an owned developer daemon |
| `submit.sh` | Atomically submit a source to the developer inbox |
| `database.sh` | Read-only service/dataframe database inspection |
| `logs.sh` | Query ECS events by run, level and diagnostic |
| `smoke.sh` | CLI, oneshot, daemon and local managed-import smoke scenarios |
| `lifecycle-smoke.sh` | Active/expiry/history, bounded retention, projection/export and ID behavior |
| `dataframe-import-load.sh` | Opt-in insert/100k and mixed/1m Native JDBC import contracts |
| `ioc-aggregate-load.sh` | Opt-in aggregate baseline/candidate regression check |
| `release-notes-context.sh` | Read-only Git/PR release inventory |
| `common.sh` | Shared ownership, path and command helpers |

```bash
make help
make context
make doctor-core
make fixture SIZE=1000 SEED=42
make smoke
make lifecycle-smoke SIZE=1000
make lifecycle-load
make dataframe-import-load-100k
make dataframe-import-load-1m
```

Inspect `make help` and the corresponding script's `--help` for required
arguments. GNU/Linux, Bash, GNU coreutils and `/proc` are the supported tool
environment; use WSL or a Linux VM on other hosts. Optional dependencies are
reported by doctor. Set `NO_COLOR` to suppress terminal colors.

## Meaning of smoke and load results

Runtime smoke exercises supported entry points with private databases and owned
files. It is distinct from deterministic Maven tests and provisioned external
transport evidence. A skipped external test is not a successful live test.

Lifecycle load uses at least 100k canonical rows, checks the deadline wave,
expiry latency/drain, retention and JVM resident envelope. It runs the existing
Xms128m/Xmx512m profile with a 1-GiB systemd memory ceiling. Its throughput floor
is a host-specific regression envelope, not hardware-neutral capacity. A new
host/JDK/storage profile requires an explicit reviewed rebaseline, not relaxed
assertions after a failed run.

Managed-import load is an opt-in contract workload. The insert profile stages
and promotes 100k rows; mixed/1m uses a private active baseline and equal shares
of insert/update/no-op/conflict. It tests Native canonical transactions, not an
alternate backend. Temporary JUnit fixtures do not open production databases.
Heap/RSS and plans belong to the declared workload/envelope.

Aggregate load passes one duplicate-heavy document through public daemon intake
and checks artifact fields, terminal registration, field origins and query plans.
An optional pre-feature JAR supplies a comparable baseline. The documented
regression envelope requires deliberate review when equipment or policy changes.

Historical optimization measurements and their scopes/limits are retained in
[native capacity](../../docs/dev/native-capacity.md). Closed one-off comparison
and Staged experimental drivers are not supported developer commands.
Quality/analyzer commands are described in [tools](../README.md) and
[build quality](../../docs/dev/build-quality.md).
