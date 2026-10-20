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

All three admitted components exercise the same signing/recovery protections.
POM checks preserve the existing zero-dependency contracts and admit only the
logging component's exact required SLF4J compile JAR. Bundle reads repeat those
checks before side effects, even when changed bytes have matching manifest
hashes. Consumer tests reject wrong repository provenance and unrelated failures
that could otherwise masquerade as successful negative dependency checks.
