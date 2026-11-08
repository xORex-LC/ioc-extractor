# com.iocextractor.platform.etl

Framework-free pipeline contracts and sequential execution. Pipeline composition
chooses stage order; individual stages own their payload operations. See the
[module README](../../../../../../../README.md) for dependency rules, diagnostic
retention, policy checkpoints and observer failure boundaries.

## Reading map

| File | Responsibility |
|---|---|
| [package-info.java](package-info.java) | Shared execution and ownership boundary |
| [Envelope.java](Envelope.java) | Payload reference, metadata, copied diagnostic list and exact counts |
| [EnvelopeMeta.java](EnvelopeMeta.java) | Run/source/stage correlation and copied extension-attribute map |
| [Stage.java](Stage.java) | One transformation and its append-only diagnostic contract |
| [Pipeline.java](Pipeline.java) | Ordered stage list and declared payload-type composition |
| [PipelineRunner.java](PipelineRunner.java) | Calling-thread execution, bounded diagnostics and post-stage policy |
| [PipelineRunResult.java](PipelineRunResult.java) | Final envelope and exact diagnostic summary |
| [PipelineObserver.java](PipelineObserver.java) | Synchronous operational callbacks and scope handles |
| [NoopPipelineObserver.java](NoopPipelineObserver.java) | Observer without effects |
| [StageId.java](StageId.java) | Stable stage identifier |
| [StageExecutionException.java](StageExecutionException.java) | Checked observer-scope close failure wrapper |

Container copies do not make payloads, attribute values or stage instances deeply
immutable. Their owners decide sharing and thread safety. Exact member contracts
belong in Javadoc; application transactions and recovery belong outside this package.
