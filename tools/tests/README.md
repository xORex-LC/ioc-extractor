# Tool contract tests

## Purpose and structure

These tests qualify repository tooling outside the Maven test universe. They do
not contribute Java application coverage or establish runtime/load/remote-release
evidence. Temporary work is owned by the test and cleaned on exit.

| Source | Coverage |
|---|---|
| [tools-contract-test.sh](tools-contract-test.sh) | Workspace/path refusal, synthetic fixture determinism, ECS queries, context receipts, submission, read-only DB inspection, syntax/help and Make/workflow wiring |
| [library-publication-test.py](library-publication-test.py) | Bundle/POM/archive admission, hashes/origins, signing, conflict/partial-publication recovery, events core handoff, guide extraction and workflow protections |

Run from the repository root:

```bash
make lint-shell
make library-publication-test
```

`lint-shell` runs ShellCheck and both packaging and tools shell suites. It does
not execute the Python publication suite; regular CI invokes that separately.
Direct shell execution is `tools/tests/tools-contract-test.sh`. Requirements are
GNU/Linux shell/Git/Java/Maven-wrapper tooling and Python 3/GnuPG for publication
contracts. See [developer tooling](../dev/README.md) and the
[CI map](../ci/README.md) for setup and owning gates.

## What the shell suite proves

Shell contracts use a disposable `.dev/tools-contract.<pid>` workspace and
private Git repositories. They exercise real fixture generation, captured/no-color
output, symlink/outside-workspace rejection, source staging, context formatting
and matching fingerprints. jq and sqlite3 checks run when those tools are present;
a pass without them does not qualify those optional branches.

The suite checks listed scripts' syntax/help, selected invalid arguments, missing
security data/key handling, release-note read-only inputs, aggregate-load wiring,
Codecov separation, pilot configuration and immutable remote-tag admission.
Some workflow checks are static text assertions, while tag admission uses a local
bare remote and peeled tag fixture. Neither proves a protected GitHub run.
No daemon, systemd activation, real SMB operation or load workload is started.

## What the publication suite proves

Publication tests use temporary bundles, mocked remote/Maven calls and disposable
GnuPG keys. Real temporary-key signing exercises subprocess/cleanup behavior;
network publication, production credentials and the release signing identity are
not used. Archive/POM checks reject coupling, changed bytes, missing artifacts,
wrong selectors and symlink/path escapes before effectful work.

Recovery cases reject remote byte conflicts and unsafe partial Central visibility,
resume the existing deployment, and upload only missing matching GitHub files.
Events cases preserve the exact core/adapter family, qualified core receipts and
source/run identity. Tests execute the workflow's admission/consumer shell where
applicable, including failure propagation through `tee` and receipt generation
only after both consumers pass.

Extraction contracts prepare the actual bilingual guide sources, reject block
count/name/parity drift, and preserve independent fixture assertions. Routing and
negative dependency checks reject wrong provenance and unrelated failures that
could falsely look like successful rejection. They test the harness, not actual
cold dependency resolution. Run the [standalone consumers](../fixtures/README.md)
through the [publication procedure](../../docs/guides/library-publication.md)
for packaged-byte/repository qualification. A local pass does not renew historical
release evidence, close TOOL-8, or qualify a second deployed service.
