# com.iocextractor.application.tck.lifecycle

## Purpose

Reusable behavior contract for storage adapters implementing canonical record
validity and expiration.

**Layer rule:** the contract depends only on framework-free application ports
and test libraries. SQL migrations and adapter-specific race tests stay with the
adapter.

## Structure

| File | Responsibility |
|---|---|
| `CanonicalRecordLifecycleContractTest.java` | One-`asOf`, active boundary, renewal, replay, restart, expiry, revision and ID non-reuse contract |

## Dependencies

**Depends on:** `ioc-application`, JUnit 5 and AssertJ.

**Consumed by:** `adapter-store-jdbc` beginning with DATA-TTL-01 P3; the JDBC
subclass executes the contract against real SQLite storage.
