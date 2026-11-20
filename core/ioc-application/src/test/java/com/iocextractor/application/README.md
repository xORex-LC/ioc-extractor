# Application tests

Framework-free service/model/port contracts using synthetic data, deterministic collaborators and temporary local files. This module uses direct JUnit tags where needed instead of depending outward on the TCK.

| Entry points / files | Responsibility |
|---|---|
| [TestDocumentWorkspace](TestDocumentWorkspace.java), [TestDocumentSourceWorkspace](TestDocumentSourceWorkspace.java) | Small in-memory preparation/source fixtures, not production spill/capacity implementations. |
| [artifact/README.md](artifact/README.md), [dataframeimport/README.md](dataframeimport/README.md), [export/README.md](export/README.md), [ingest/README.md](ingest/README.md) | Identity/lifecycle, structured delivery, immutable formation and whole-source recovery suites. |
| [pipeline/README.md](pipeline/README.md), [sync/README.md](sync/README.md) | Stage/metadata/trace/checkpoint and remote coordination/local landing suites. |
| [cadence/CadenceSourceTest](cadence/CadenceSourceTest.java), [maintenance/RetentionPolicyTest](maintenance/RetentionPolicyTest.java), [maintenance/RetentionServiceTest](maintenance/RetentionServiceTest.java) | Clock-injected cadence and age/count unit selection/sweep. |
| [observation/ObservationAdmissionServiceTest](observation/ObservationAdmissionServiceTest.java), [service/IocExtractionServiceFactoryTest](service/IocExtractionServiceFactoryTest.java), [port/out/artifact/ArtifactProjectionResultTest](port/out/artifact/ArtifactProjectionResultTest.java) | Cross-store admission handshake, run factory and projection result contracts. |

All local test suites follow Surefire naming; no local Failsafe/external shell is implied. Run from the repository root:

```bash
make test-module MODULE=core/ioc-application
```

Use `make test-one MODULE=core/ioc-application TEST=IngestionServiceTest` for a focused suite and `make verify` for downstream integration and authoritative reactor acceptance. Test helpers model port outcomes and inject failures; sync tests also exercise temporary local files. They do not prove JDBC transactions, actual parser/SMB/Camel behavior, crash/power-loss durability or whole-service memory bounds.

Concrete JDBC TCK bindings live in the storage adapter, and bootstrap/other adapters own real composition/format/transport tests. Report local versus downstream evidence explicitly. A percentage or green fake-backed test is not permission to close an unrelated operational/qualification issue.

[Test map](README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
