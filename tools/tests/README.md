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
