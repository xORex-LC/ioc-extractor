# core/ioc-application-tck

## Purpose and boundary

Reusable behavioral contracts for driven application ports and shared JUnit test
annotations. The internal reactor artifact is `com.iocextractor:ioc-application-tck`.
It exports selected contract bases, not the application module's internal test suite.
A passing consumer establishes the exercised contract scenarios against its fixture;
it does not establish complete behavioral equivalence of all implementations.

The TCK is an ordinary JAR with contracts in `src/main/java`. Main-class reactor
availability avoids coupling downstream tests to an attached `test-jar` produced
only at `package`. Consumers add the TCK in **test scope**; its JUnit/AssertJ toolkit
is compile-scoped here so it remains available transitively in consumer tests.
This is test support, not a service runtime component or admitted standalone library.

## Structure

| Directory | Responsibility |
|---|---|
| [TCK namespace](src/main/java/com/iocextractor/application/tck/README.md) | Contract and consumer map |
| [artifact](src/main/java/com/iocextractor/application/tck/artifact/README.md) | Canonical identity formula/epoch baseline |
| [ingest](src/main/java/com/iocextractor/application/tck/ingest/README.md) | Ingestion ledger transitions, occurrence identity and terminal race |
| [export](src/main/java/com/iocextractor/application/tck/export/README.md) | Formation ledger replay/CAS and terminal progress |
| [lifecycle](src/main/java/com/iocextractor/application/tck/lifecycle/README.md) | Canonical validity, renewal, replay, expiry and ID non-reuse |
| [dataframeimport](src/main/java/com/iocextractor/application/tck/dataframeimport/README.md) | Delivery ordering/checkpoints and canonical receipt replay |
| [junit](src/main/java/com/iocextractor/application/tck/junit/README.md) | Shared lifecycle/semantic annotations |

There are no local `src/test` or resource trees. Abstract contracts execute through
concrete consumer subclasses; the package maps link those implementations.

## Dependencies and build scope

Direct application dependencies are `ioc-application` and `ioc-platform-diagnostics`;
the export contract asserts its `DiagnosticException` behavior. The parent manages
versions/Java 21. JUnit Jupiter and AssertJ are intentionally compile-scoped;
application/domain/platform contracts arrive through normal inward dependencies.
There is no JDBC, Spring or concrete adapter implementation in this module.

The POM skips local SpotBugs analysis because its main bytecode is reusable test
support. Production coverage, SpotBugs, CPD and PMD aggregate scopes exclude this
module. Its inherited checks execute in downstream test reports and can exercise
production code there; excluding the support artifact does not exempt consumers
from the test-report integrity gate. See [build quality](../../docs/dev/build-quality.md).

## Adding a consumer or contract

1. Add `ioc-application-tck` as a test-scoped dependency, with its version supplied
   by parent management. Keep production modules independent of this test toolkit.
2. Extend the relevant abstract contract in the adapter's `src/test/java` and
   implement its factory. Each fixture must start clean, expose all required ports
   over the same store, apply needed schema/activation setup and honor supplied time.
3. Retain inherited checks. Concrete storage/filesystem suites use an `*IT` name
   and `@IntegrationTest`; `@ContractTest` is inherited from the base. The adapter
   owns cleanup of every datasource, file or worker created by the fixture.
4. Add adapter-specific migration, transaction failure, recovery and concurrency
   cases alongside the inherited baseline. A new reusable port contract belongs
   here when its behavior applies to multiple implementations; keep backend details
   with the adapter. Review lifecycle registry/report scope with suite changes.

Composed tags describe semantics; Maven naming controls discovery. `@ExternalTest`
alone neither provisions a target nor disables execution. Follow the explicit
property-gating and evidence rules in [TESTING](../../docs/TESTING.md).

## Verification

Run from the repository root:

```bash
make test-integration-module MODULE=adapters/adapter-store-jdbc
make test-one MODULE=adapters/adapter-ingest TEST=FileIngestionLedgerIT
```

The JDBC module consumes all six bases; the file-ingest module additionally consumes
the ingestion base. Running only the TCK module does not qualify those adapters.
`make verify` is the full deterministic gate. Live transport, deployment and capacity
claims require their own qualified scenarios; these fixtures do not supply them.
