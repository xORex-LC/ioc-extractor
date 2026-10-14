# com.iocextractor.application.artifact.lifecycle

## Purpose

Framework-free language and orchestration for canonical record validity,
lifecycle behavior and mutable projection ownership. It defines absolute
validity decisions and half-open activity without depending on JDBC, Spring,
CSV or scheduler APIs.

**Layer rule:** this package owns application semantics. Storage and runtime
implement them through inward-facing ports.

## Structure

| Files | Responsibility |
|---|---|
| `RecordValidityPolicy.java` / `FixedRecordValidityPolicy.java` | Strategy seam and fixed V1 policy |
| `EffectiveTime.java` / `LifecycleDeadline.java` / `ValidityDecision.java` | Explicit transaction time and absolute boundary |
| `LifecycleTimeSource.java` | Injected UTC time boundary; runtime clock safety remains outside the model |
| `LifecycleId.java` / `ObservationId.java` / `ConfirmationReceiptId.java` | Durable, non-interchangeable identities |
| `RecordLifecycle.java` / `LifecycleCloseReason.java` | Active lifecycle invariants and close vocabulary |
| `ProjectionGeneration.java` | Mutable-projection work version |
| `Canonical*Confirmation.java` / `ConfirmationReceiptContext.java` / `LifecycleWriteResult.java` | Identity-resolved write command, bounded receipt facts and classified durable outcome |
| `ActiveArtifact*.java` / `ExpiryBatchResult.java` | Active snapshot and bounded reconciliation results |
| `LifecycleControlState.java` | One-way persisted activation model |
| `ArtifactProjectionState.java` / `ProjectionAcknowledgement.java` | Required work and monotonic installed coverage |
| `GenerationOwnedArtifactProjection.java` | Shared per-artifact snapshot/build/install/ack owner and bounded successful-result reuse |
| `ArtifactProjectionConvergenceService.java` | Durable pending-work discovery and diagnostic emission through the owner |

## Dependencies

**Depends on:** JDK, inward-facing application values/ports and platform
diagnostic/error/event contracts.

**Must not import:** Spring, JDBC, SQL, CSV, filesystem or Actuator types.
