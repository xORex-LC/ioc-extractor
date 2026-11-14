# core

## Purpose and boundary

The inward IOC modules: domain vocabulary and rules, shared pure preparation,
application use cases/ports and reusable application-port test support. `core/`
is a directory grouping; the repository root POM declares its Maven modules.
There is no separate `core` aggregator POM.

Production dependencies point inward. Framework integration and composition stay
in adapters and bootstrap. Framework-free application orchestration can invoke
side-effecting ports; it is not a promise of pure computation. Test support has
its own JUnit/AssertJ dependency boundary and stays outside production runtime use.

## Modules and reading order

| Module | Responsibility | Allowed dependency direction |
|---|---|---|
| [ioc-domain](ioc-domain/README.md) | IOC values, refang, extraction, attribution, features and mask rules | JDK only in production |
| [ioc-processing](ioc-processing/README.md) | Classified views, exact-cell parsing, semantic reuse and field mapping | Domain and platform errors |
| [ioc-application](ioc-application/README.md) | Use cases, driven/driving ports and extraction/import/export/lifecycle/sync coordination | Domain, processing and framework-free platform contracts |
| [ioc-application-tck](ioc-application-tck/README.md) | Reusable port-contract bases and composed JUnit annotations | Application, IOC diagnostics and compile-scoped test toolkit; consumers depend in test scope |

Domain and processing do not depend on application or its TCK. Application does
not depend on adapters/bootstrap or the TCK. The TCK imports application contracts;
concrete adapters implement them and run inherited checks in their own test trees.
Read each module's README for package structure, ownership, entry points and guards.

## Verification and related docs

Run commands from the repository root. `make test-module MODULE=core/ioc-domain`
selects a module and its upstreams; use the analogous module path for processing or
application. Building/testing the TCK by itself compiles test support but does not
execute a concrete adapter contract. Its README identifies the consumer suites.
`make verify` checks the full deterministic reactor.

Project-wide rules belong to [architecture](../docs/ARCHITECTURE.md),
[module map](../docs/MODULARIZATION.md), [boundaries](../docs/BOUNDARIES.md) and
[test policy](../docs/TESTING.md). Cross-module behavior belongs to capability docs
such as [processing](../docs/dev/processing.md) and [storage](../docs/dev/storage.md).
