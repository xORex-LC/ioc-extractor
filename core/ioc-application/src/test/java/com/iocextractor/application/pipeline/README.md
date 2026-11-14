# Pipeline application tests

Application metadata/completion/ownership tests; generic pipeline mechanics remain in platform-etl.

| Entry points / files | Responsibility |
|---|---|
| [CompletionStatusTest](CompletionStatusTest.java), [PipelineModelArchitectureTest](PipelineModelArchitectureTest.java) | Exact diagnostic-summary completion and dependency/port model boundaries. |
| [payload/PayloadTypeTest](payload/PayloadTypeTest.java), [stage/README.md](stage/README.md) | Payload invariants and individual operation/checkpoint tests. |

The small document/source workspace helpers at the test root intentionally materialize synthetic data. Passing these model/stage tests does not qualify production disk-spill bounds, whole-source streaming or full-service capacity. Bootstrap and adapters supply production assembly/workspace evidence.

[Test map](../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
