# packaging contract tests

## Purpose

Read-only and temporary-directory checks for the host lifecycle boundary. The
suite pins destructive-target validation, installation-marker semantics,
service-account safety and rendered systemd arguments without provisioning a
real host or starting the daemon.

## Run

```bash
packaging/tests/packaging-contract-test.sh
```

CI runs this suite together with ShellCheck and `bash -n`. Full install/rollback
behaviour still requires a disposable systemd test host.
