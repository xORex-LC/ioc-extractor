# com.iocextractor.application.port.out.artifact.lifecycle

## Purpose

Client-shaped driven ports for canonical record lifecycle persistence and
recovery. Implementations may use JDBC/SQLite, but those mechanisms never enter
the contracts.

**Layer rule:** ports describe atomic business guarantees; they do not expose
tables, SQL transactions, scheduler APIs or filesystem paths.

## Structure

| File | Responsibility |
|---|---|
| `CanonicalArtifactWriter.java` | Atomic confirmation, idempotency, lifecycle and revision contract |
| `ActiveArtifactReader.java` | Active-only artifact snapshot at one explicit `asOf` |
| `ExpiredArtifactStore.java` | Nearest deadline and bounded archive/delete transaction |
| `LifecycleControlStore.java` | Persisted one-way activation CAS |
| `ArtifactProjectionWorkStore.java` | Mutable projection generation and acknowledgement CAS |

## Dependencies

**Depends on:** framework-free lifecycle and artifact values from
`ioc-application`.

**Implemented by:** storage adapters; V1 uses `adapter-store-jdbc` in P2/P3.
