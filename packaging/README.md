# packaging

Installing and operating **ioc-extractor** as a `systemd` daemon on Debian 11+.
Release `0.1.0`.

## Contents

| File | Purpose |
|---|---|
| `install.sh` | From-scratch installer (root): JDK 21 (manual), user, directories, jar, config, systemd unit. |
| `uninstall.sh` | Stop/remove the service; `--purge` also deletes the directory and the user. |
| `templates/ioc-extractor.service` | Unit template; placeholders `@PREFIX@/@VERSION@/@JAVA_BIN@/@USER@` are substituted at install time. |
| `templates/application.yml` | Production override (daemon mode); deployed to `<prefix>/etc/application.yml`. |
| `templates/ioc-extractor.env` | `EnvironmentFile` with `JAVA_OPTS`. |

**Layer rule:** deployment material only (shell + templates), no Java code or
business logic. Daemon mode and configuration are documented in
[docs/ingestion.md](../docs/ingestion.md) and
[application.yml](../bootstrap/ioc-app/src/main/resources/application.yml).

## Java 21 — manual install only

Debian 11 repositories do not ship JDK 21, so the installer installs **Temurin 21
from a tarball** (no apt repositories are added) into `<prefix>/jdk` —
self-contained:

- offline host: `--jdk-tarball /path/OpenJDK21-jdk_x64_linux_hotspot.tar.gz`;
- host with internet: the tarball is downloaded from Adoptium automatically (or `--jdk-url`);
- if the host already has `java ≥ 21`: `--system-java`.

## Layout (single-dir)

```text
<prefix>/                         # default /opt/ioc-extractor
├── jdk/                          # Temurin 21 (installed manually)
├── lib/ioc-app-0.1.0.jar
├── etc/application.yml           # override (operator-editable)
├── etc/ioc-extractor.env         # JAVA_OPTS
├── var/                          # inbox/ processing/ done/ failed/ ledger/ logs/
└── dataframe/                    # partitions/ + generated artifacts + lookup seed
```

The service `WorkingDirectory` is `<prefix>`, so the application's relative paths
(`./var/...`, `./dataframe/...`) resolve inside the install directory.

## Install

```bash
# 1) build the release jar (requires JDK 21; see the root README / CLAUDE.md)
./mvnw -q -DskipTests package        # -> bootstrap/ioc-app/target/ioc-app-0.1.0.jar

# 2) install (the jar is auto-discovered next to the script or in target/)
sudo packaging/install.sh                         # prompts for the directory (default /opt/ioc-extractor)
sudo packaging/install.sh --prefix /opt/ioc-extractor
sudo packaging/install.sh --jdk-tarball /tmp/temurin21.tar.gz   # offline host
sudo packaging/install.sh --jar /path/ioc-app-0.1.0.jar --no-start
```

The installer is **idempotent**: re-running upgrades the jar and the unit; an
existing `etc/application.yml` is not overwritten (a `*.new` is written next to it)
unless `--force` is given. The installer **refuses** to install into a directory
containing `pom.xml`/`.git` (a source tree) without `--force`.

## Operate

```bash
systemctl status ioc-extractor
journalctl -u ioc-extractor -f                 # ECS-JSON logs (+ <prefix>/var/logs/)
cp report.htm /opt/ioc-extractor/var/inbox/    # submit a source for processing
```

Flow: `var/inbox` → (quiet-period) → `var/processing` → `var/done` (or `var/failed`),
a partition is written to `dataframe/partitions/`, and periodic aggregation (stable
ids) produces the canonical CSVs from `application.yml`: `masks_list_generated.csv`,
`ip_list_generated.csv`, `address_blacklist_generated.csv` and
`hashes_list_generated.csv`.

After editing `etc/application.yml`: `systemctl restart ioc-extractor`.

## Uninstall

```bash
sudo packaging/uninstall.sh                 # remove the service, keep data
sudo packaging/uninstall.sh --purge         # + delete the directory and the ioc user
```

## Out of scope (0.1.0)

A `.deb` package with maintainer scripts; OS-level log rotation (currently the
built-in Logback rolling appender).
