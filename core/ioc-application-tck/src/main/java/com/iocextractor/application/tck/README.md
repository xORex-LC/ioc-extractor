# com.iocextractor.application.tck

## Responsibility and structure

Shared executable port contracts and JUnit metadata. This namespace has no direct
Java declarations; six subpackages contain the contract bases and annotations.

| Package | Contract / current consumers |
|---|---|
| [artifact](artifact/README.md) | Identity-store baseline; JDBC identity suite |
| [ingest](ingest/README.md) | Ingestion ledger; JDBC and file-ledger suites |
| [export](export/README.md) | Export formation ledger/progress; JDBC suite |
| [lifecycle](lifecycle/README.md) | Canonical lifecycle writer/reader/expiry; JDBC suite |
| [dataframeimport](dataframeimport/README.md) | Delivery ledger and canonical import writer; JDBC suites |
| [junit](junit/README.md) | Contract/integration/e2e/external/slow metadata used by adapter/bootstrap tests |

## Contract ownership

Application ports and Javadoc define supported guarantees. TCK bases select common
observable scenarios, while adapter factories supply the implementation, isolated
state and cleanup. A fixture callback is part of the evidence and must assert actual
durable effects; a no-op callback cannot qualify a writer.

Tests are inherited into consumer reports. No abstract base here owns an independent
Surefire/Failsafe suite result. The [module README](../../../../../../../README.md)
explains test-scope dependencies, consumer setup and execution; [TESTING](../../../../../../../../../docs/TESTING.md)
owns naming, tags, report integrity and coverage policy.
