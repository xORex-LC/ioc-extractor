# Tool contract tests

`tools-contract-test.sh` checks developer-tool ownership/safety, deterministic
fixtures, workspace-aware ECS queries, machine-readable context and syntax/help
boundaries without starting systemd or external integrations.

```bash
tools/tests/tools-contract-test.sh
```

Runtime scenarios run separately through [developer tools](../dev/README.md).
Syntax/help checks are not daemon, load or transport evidence.

`library-publication-test.py` checks offline bundle immutability, artifact
conflicts, partial publication and Central-deployment reuse. It uses a temporary
GnuPG key; network access and working secrets are not used. The publication
fixture remains independent of IOC storage experiments.

All six release-admitted components preserve signing/recovery protections.
Events also qualifies both exact POM contracts, local two-bundle assembly,
whole dependency graphs and per-file provenance. Tests exercise real disposable-key
signing for both events components, successful core-run provenance, qualification
receipt tampering, core-first writes and immutable two-bundle recovery. The actual
workflow admission Python and consumer shell are exercised, including failures
through `tee` and receipt generation only after both consumers pass.
POM checks preserve JDK-only contracts
and admit only the exact required APIs for each descriptor. Bundle reads repeat
those checks before side effects, even when changed bytes have matching manifest
hashes. Consumer tests reject wrong repository provenance, wrong core identity/
version, empty fixture admission and unrelated failures that could masquerade as
successful negative dependency checks. Real cold consumers run separately via
the [publication guide](../../docs/guides/library-publication.md).
