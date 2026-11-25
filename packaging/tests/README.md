# Packaging contract tests

## Purpose and boundary

[packaging-contract-test.sh](packaging-contract-test.sh) checks installation-layout
helpers, source declarations and rendered host templates using temporary fixtures.
It sources `install-layout.sh`, not the privileged installer/activation entry points.
[deploy-local-activation-test.sh](deploy-local-activation-test.sh) executes the
local activation script with its host paths redirected to a disposable fixture.
The copied script accepts only that fixture's canonical prefix, including in a
checkout under `/home`. The layout suite exercises the production prefix policy.
It substitutes systemd, ownership-changing commands and the health endpoint.
The fixture uses real SQLite files and GNU tar. The tests run without provisioning
an account or starting the installed daemon.

## Fixtures and exercised contracts

| Area | Evidence |
|---|---|
| Prefix/source layout | Protected roots, unsupported characters and symlink traversal rejected; dedicated prefixes accepted; checkout ancestry recognized |
| Installation identity | Marker creation/field uniqueness/user match, strict pre-marker shape and old single-directory recognition |
| Account | UID 0 rejected |
| Production preset | Fresh versus classpath lifecycle, import dirs, event/reconcile/stability settings and config preservation declarations |
| Candidate conflict | Paths, timestamps/digests and review commands without leaking synthetic config contents |
| Preflight and privileged activation | Reject config or a concurrent config apply before reaching stop. Verify staged files, load order and the healthy candidate |
| Recovery | Fail stop, archive creation and verification, file replacement, reload, start and health. Check signal rollback, prior inactivity, missing helper, both DBs, POSIX file modes and extended attributes |
| Recovery identity and retention | Retry a release ID after failure, retain the matching previous release and incomplete attempts, report stop/restore faults without starting the previous binary |
| Rendering | Explicit server-port argument and key sandbox/ExecCondition settings; no unresolved config-helper tokens; staged YAML suffix and validation environment |
| Syntax | Shell syntax checks and `systemd-analyze verify` when available |

Scratch uses `mktemp` and an ignored `packaging/target` fixture for symlink tests;
EXIT cleanup removes the owned fixture directories. `systemd-analyze verify` runs
on a rendered temporary unit when the tool is available. It validates unit syntax,
not that the daemon can start under its sandbox.

## Running and wider checks

Run from the repository root:

```bash
make lint-shell
```

For a focused rerun use `packaging/tests/packaging-contract-test.sh` or
`packaging/tests/deploy-local-activation-test.sh`.
The Make target calls [tools/ci/packaging.sh](../../tools/ci/packaging.sh): ShellCheck over
its declared script globs, then both packaging suites and tools-layer contracts. The leaf's
ShellCheck arguments currently include `templates/ioc`; `templates/ioc-config`
is rendered and syntax-checked here but is not in that ShellCheck glob. Run an
explicit ShellCheck on it when changing that helper.

[ConfigurationDocumentationContractTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java)
checks YAML binding/semantic/registry contracts through Maven. Tools contracts
check release-workflow tag-source wiring; they do not execute a real GitHub draft
publication. A valid local helper dry-run is not remote-tag/asset qualification.

Qualify the installed account, real systemd activation, permissions, sandbox,
native loading and live transport on a designated target. The shell suites prove
the local activation and failure contract for
[OPS-13](../../docs/KNOWN-ISSUES.md#ops-13). Build provenance has a separate open
[TOOL-11](../../docs/KNOWN-ISSUES.md#tool-11) limitation. See the
[packaging reference](../README.md) and [deployment guide](../../docs/guides/deployment.md).
