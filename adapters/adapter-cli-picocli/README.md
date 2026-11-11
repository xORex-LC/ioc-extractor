# adapters/adapter-cli-picocli

## Purpose and boundary

Inbound CLI adapter, `com.iocextractor:ioc-adapter-cli-picocli`. It translates
picocli arguments into application driving-port calls and renders operator
results. Bootstrap owns configuration and object composition; application use
cases own extraction, export recovery, import coordination and remote delivery.
This module is a regular reactor JAR. The runnable application lives in
[bootstrap/ioc-app](../../bootstrap/ioc-app/README.md).

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Module dependencies; versions inherited from the parent |
| [Production package](src/main/java/com/iocextractor/adapter/in/cli/README.md) | Command tree, early startup, lazy use-case resolution and output contracts |
| [Test package](src/test/java/com/iocextractor/adapter/in/cli/README.md) | Parser, command, output and Spring-construction contracts |
| [CLI fixtures](src/test/resources/consumer/cli/README.md) | Independent expected text for supported CLI output |
| [Build identity fixture](src/test/resources/META-INF/build-info.properties) | Test-only packaged identity for version rendering |

The Java sources form one production package and one matching test package.
Intermediate `src/main/java` and namespace directories only locate those packages.

## Dependencies

- `ioc-application` supplies driving ports and their command/result values.
- `ioc-platform-diagnostics-api`, `ioc-platform-diagnostics` and
  `ioc-platform-observability` supply diagnostic summaries and typed log events.
- picocli defines parsing/help; its Spring Boot starter supplies the Spring-aware
  command factory. Spring Boot supplies components, conditional lifecycle and
  `ObjectProvider` injection. SLF4J is the logging API.
- Jackson databind parses the separate daemon's health JSON. HTTP uses JDK
  `java.net.http`; this module does not provide an HTTP server.
- `ioc-application-tck` is test-scoped and supplies composed test annotations;
  the parent supplies shared test dependencies.

The POM is the dependency authority. Keep database, CSV, document parsing and
SMB implementations behind application ports. Do not import bootstrap or sibling
outbound adapters into command code. Bootstrap depends on this module and wires
its ports; the dependency must continue pointing inward.

## Entry points and contracts

The command root is `ioc`, with `extract`, `export`, `import`, `sync` and `health`.
There are two execution paths:

1. Bootstrap calls `EarlyCliLauncher` before starting Spring. Annotated command
   metadata supplies help, version and syntax checking without resolving use
   cases. A valid `health` command queries the daemon through this early path.
2. Commands requiring application state proceed to Spring. `CliRunner` executes
   the Spring-managed command graph in oneshot mode and exposes its exit code
   through `ExitCodeGenerator`. Daemon scheduling belongs to bootstrap.

Export and sync selection validators are injected directly and run before their
lazy storage/transport-backed use cases are resolved. Import commands obtain
optional capability beans through providers and fail explicitly when unavailable.
Metadata-only constructor shells must never execute an application operation.
See the production package for the precise startup, output and exit boundaries.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-cli-picocli
make test-one MODULE=adapters/adapter-cli-picocli TEST=SpringCommandConstructionIT
```

The first command runs Surefire tests with upstream modules; the second selects
the Failsafe construction contract. Full reactor acceptance uses `make verify`.

Related contracts: [configuration](../../docs/guides/configuration.md), [artifact export](../../docs/dev/artifact-export.md), [managed import](../../docs/guides/dataframe-import.md), [remote sync](../../docs/guides/remote-storage-sync.md) and [testing policy](../../docs/TESTING.md).
