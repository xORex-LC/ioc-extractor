# CLI adapter tests

## Purpose and boundary

Tests for [the production CLI package](../../../../../../../main/java/com/iocextractor/adapter/in/cli/README.md).
Use recording application ports and local fixtures to verify translation and
observable output. The HTTP health tests use a temporary loopback server; no
installed daemon, production database or remote SMB endpoint is required.

## Structure and coverage

| Test / helper | Contract |
|---|---|
| [EarlyCliLauncherTest.java](EarlyCliLauncherTest.java) | Pre-Spring help/version, parse errors, sync-group help and business/daemon delegation |
| [ApplicationBuildInfoReaderTest.java](ApplicationBuildInfoReaderTest.java), [BuildInfoVersionProviderTest.java](BuildInfoVersionProviderTest.java) | Required/optional metadata, malformed identity and version rendering |
| [ExtractCommandTest.java](ExtractCommandTest.java) | Run correlation, diagnostic/completion summaries and exit mapping across completion statuses |
| [ExportCommandTest.java](ExportCommandTest.java) | Use-case delegation, help laziness and profile rejection before provider resolution |
| [SyncCommandTest.java](SyncCommandTest.java) | Filter forwarding, output counters, failure exit, full preflight and fetch-before-publish ordering |
| [HealthCommandTest.java](HealthCommandTest.java) | Loopback HTTP status/JSON rendering, component paths, endpoint selection and handled failures |
| [SpringCommandConstructionIT.java](SpringCommandConstructionIT.java) | Real Spring constructor selection for import/sync commands; providers are injected without executing their capabilities |
| [CliGolden.java](CliGolden.java) | UTF-8 expected-text loader with platform line-ending adaptation |

The construction IT does not prove managed-import execution or a configured
storage/transport graph. This package has no dedicated import validate/status/
replay execution suite; broader import behavior is tested by application and
bootstrap contracts. Keep that distinction when extending coverage.

## Fixtures and ownership

[consumer/cli](../../../../../../resources/consumer/cli/README.md) contains independent expected
operator text. The [build-info fixture](../../../../../../resources/META-INF/build-info.properties)
supplies deterministic classpath identity; it is not release provenance.

Preserve both picocli-writer and process-stream capture where required by the
command. Release owned HTTP servers and restore replaced streams after each test.
Use bounded waits/timeouts for asynchronous fixtures as required by
[TESTING.md](../../../../../../../../../../docs/TESTING.md).

## Verification

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-cli-picocli
make test-one MODULE=adapters/adapter-cli-picocli TEST=SpringCommandConstructionIT
```

Surefire discovers `*Test`; Failsafe discovers the annotated `*IT`. Adding a
command should include behavior tests for parsing, argument/result mapping,
meaningful failures and output changes; a constructor assertion alone is not
execution coverage. Review golden changes as consumer-contract changes.
