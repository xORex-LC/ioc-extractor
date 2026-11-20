# bootstrap/ioc-app

## Purpose and boundary

The executable Spring Boot application and composition root. Maven artifact
`com.iocextractor:ioc-app` assembles framework-free core services and concrete
adapters into oneshot commands or the long-running daemon. Inner modules must
not depend on this module.

This module owns external configuration, IOC-specific wiring, startup/shutdown,
control-event listeners and operational views. IOC algorithms and durable use
cases belong to core; storage/transport/parser mechanics belong to adapters.
The bootstrap Java package currently contains several capability groups in one
flat directory. Its README provides a responsibility map without changing that
layout.

## Structure and reading order

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Runtime assembly, executable packaging, build identity and test support |
| [Application package](src/main/java/com/iocextractor/README.md) | Process entry point and early command paths |
| [Bootstrap package](src/main/java/com/iocextractor/bootstrap/README.md) | Configuration, graph segments, startup barriers, workers and health |
| [Runtime resources](src/main/resources/README.md) | Classpath defaults, logging and Spring registration/metadata |
| [Test tree](src/test/java/com/iocextractor/README.md) | Repository contracts and composed runtime scenarios |
| [Test resources](src/test/resources/README.md) | Isolated profiles, golden wires and independent consumers |

Start at the application package for process behavior, then use the bootstrap
map to select a capability. Cross-module algorithms remain in `docs/dev/`;
operator procedures remain in `docs/guides/`.

## Dependencies and packaging

The POM selects core, platform and adapter modules plus focused Boot starters
for base runtime, validation, actuator and Web MVC. Direct framework APIs used by
composition include Logback/SLF4J, HikariCP, Jakarta Validation, Commons CSV and
Camel API. Shared versions belong to the parent, not another README inventory.
The configuration processor is optional; TCK, Boot test support and ArchUnit are
test-scoped. Dependency versions and transitive parser/crypto/database families
must be assessed from the effective runtime tree.

Spring JDBC arrives through the JDBC adapter, but a Boot JDBC auto-configuration
module is not part of the runtime graph. Datasources, migrations, admission and
writers are wired explicitly. Bootstrap can reference concrete adapters to bind
ports; their SDK types must not leak inward into core contracts.

This is the reactor's runnable JAR. The Boot plugin repackages it and generates
`META-INF/build-info.properties`. Local builds record Maven identity and UTC time;
`-Dbuild.commit=<full-hex-object-id>` activates the validated commit-metadata
profile. Runtime does not need `.git`. CLI version reads that resource before
Spring; actuator exposes available build identity. IDE execution may lack generated
build metadata without preventing runtime bean construction.

## Runtime boundaries

`IocExtractorApplication` checks internal YAML/semantic-validation requests before
lightweight picocli help/version/health/syntax paths. Ordinary commands then enter
Spring; oneshot closes the context and exits, while daemon mode keeps it running.
The semantic checker creates only its explicit configuration/preflight graph;
it does not component-scan the application. See the application-package README
for the ordering and side-effect boundaries.

Daemon startup must complete migration, recovery and canonical-data admission
before opening intake. Background hints only accelerate work: ledgers, durable
revisions/deadlines and periodic reconcile remain authoritative. Export formation,
mutable projection, lifecycle retention, import retention and file maintenance
have distinct owners even when they share configuration or executors.

The shipped web surface is loopback actuator health/info in daemon mode, not a
public IOC REST API. `runtime.mode` controls process/web behavior; observability
mode selects logging defaults through Spring profiles. Keep those settings
explicitly aligned in deployment configuration.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=bootstrap/ioc-app
make test-integration-module MODULE=bootstrap/ioc-app
```

Full acceptance uses `make verify`. The tests exercise deterministic local
composition and consumer contracts; they do not qualify a production host,
live SMB server or reference-load profile. See
[architecture](../../docs/ARCHITECTURE.md), [module boundaries](../../docs/BOUNDARIES.md),
[configuration](../../docs/guides/configuration.md), [CLI](../../adapters/adapter-cli-picocli/README.md),
[deployment](../../docs/guides/deployment.md) and [testing policy](../../docs/TESTING.md).
