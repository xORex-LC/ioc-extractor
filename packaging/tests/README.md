# Packaging contract tests

## Purpose and boundary

[packaging-contract-test.sh](packaging-contract-test.sh) checks installation-layout
helpers, source declarations and rendered host templates using temporary fixtures.
It sources `install-layout.sh`, not the privileged installer/activation entry points.
It never provisions an account/JDK, starts the installed daemon or deletes a real
installation. Treat source-presence assertions as narrower evidence than executing
a complete failure/rollback scenario.

## Fixtures and exercised contracts

| Area | Evidence |
|---|---|
| Prefix/source layout | Protected roots, unsupported characters and symlink traversal rejected; dedicated prefixes accepted; checkout ancestry recognized |
| Installation identity | Marker creation/field uniqueness/user match, strict pre-marker shape and old single-directory recognition |
| Account | UID 0 rejected |
| Production preset | Fresh versus classpath lifecycle, import dirs, event/reconcile/stability settings and config preservation declarations |
| Candidate conflict | Paths, timestamps/digests and review commands without leaking synthetic config contents |
| Recovery declarations | Unit sidecar, tar ACL/xattr capture/restore and paired backup pruning remain present in source |
| Rendering | Explicit server-port argument and key sandbox/ExecCondition settings; no unresolved config-helper tokens; staged YAML suffix and validation environment |
| Ordering/syntax | Config preflight source position precedes stop/backup; shell syntax checks |

Scratch uses `mktemp` and an ignored `packaging/target` fixture for symlink tests;
EXIT cleanup removes the owned fixture directories. `systemd-analyze verify` runs
on a rendered temporary unit when the tool is available. It validates unit syntax,
not that the daemon can start under its sandbox.

## Running and wider checks

Run from the repository root:

```bash
make lint-shell
```

For a focused rerun use `packaging/tests/packaging-contract-test.sh`.
The Make target calls [tools/ci/packaging.sh](../../tools/ci/packaging.sh): ShellCheck over
its declared script globs, then this suite and tools-layer contracts. The leaf's
ShellCheck arguments currently include `templates/ioc`; `templates/ioc-config`
is rendered and syntax-checked here but is not in that ShellCheck glob. Run an
explicit ShellCheck on it when changing that helper.

[ConfigurationDocumentationContractTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java)
checks YAML binding/semantic/registry contracts through Maven. Tools contracts
check release-workflow tag-source wiring; they do not execute a real GitHub draft
publication. A valid local helper dry-run is not remote-tag/asset qualification.

Full install, upgrade, failure injection, DB/config recovery, permissions, host
sandbox/native loading and live transport still need an isolated systemd target.
[OPS-13](../../docs/KNOWN-ISSUES.md#ops-13) and [TOOL-11](../../docs/KNOWN-ISSUES.md#tool-11)
remain open despite a green static/synthetic gate. See the
[packaging reference](../README.md) and [deployment guide](../../docs/guides/deployment.md).
