# com.iocextractor.application.artifact.lifecycle

## Purpose

Framework-free language for canonical record validity and lifecycle behavior.
It defines absolute validity decisions and half-open activity without depending
on JDBC, Spring, CSV or scheduler APIs.

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
| `ArtifactProjectionState.java` / `ProjectionAcknowledgement.java` | Projection convergence and CAS evidence |

## Dependencies

**Depends on:** JDK value types and existing application artifact values only.

**Must not import:** Spring, JDBC, SQL, CSV, filesystem or Actuator types.
