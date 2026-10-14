---
title: "DATA-AGGREGATE-01 — dependency assessment"
version: "0.3.0"
status: "Assessed; no new dependency required"
document_type: "Design alternatives"
source_of_truth: false
language: "en"
---

# Dependency assessment

The owner explicitly requested evaluating Java libraries/frameworks before adding
custom infrastructure. The following choices are design judgments for this
repository, based on the existing implementation and primary documentation
reviewed on 2026-09-21. No dependency change is made or implied.

## Reuse first

| Facility | Decision and boundary |
|---|---|
| Published ioc-platform-concurrency | Reuse existing keyed exclusion/execution. No API change or republication. It owns process-local coordination, not durable source precedence. |
| Existing SQLite/JDBC adapter | Reuse canonical write transactions, migrations, identity, receipts and lifecycle mutation collaborators. SQLite serializes writers; short transactions protect row and origin together. Conditional UPSERT is available where its conflict semantics match the operation; it does not replace lifecycle handling. [SQLite isolation](https://www.sqlite.org/isolation.html), [UPSERT](https://www.sqlite.org/lang_upsert.html). |
| Spring JDBC | Reuse current JDBC mapping/binding/resource management in the adapter. Parameterize SQL values; do not create a second persistence stack. [JDBC core](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html). |
| Existing transaction boundary | Keep JdbcLifecycleTransactions and its caller-owned connection. TransactionTemplate is a supported alternative for programmatic Spring transactions, but adding it around independently acquired connections would not make them one transaction. No broad transaction rewrite is justified here. [Programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html). |
| Current RE2J/PatternEngine, CSV mapper/parser, validators and registries | Extend supported marker patterns and compiled policy/type bindings. Avoid another regex engine, CSV parser or expression language for this schema. |
| Existing journal/reconcile/event mechanisms | Extend typed ports and existing recovery owners. Events remain hints after commit; no event broker is necessary to compare two source observations. |

## Considered additions

| Candidate | What it supplies | Why not introduce it for this feature |
|---|---|---|
| Spring Batch | Job/step processing and parallelization/partitioning options | Would overlap existing ingest/import ledgers and recovery. It does not decide which source name is authoritative. Reconsider for a separately approved batch-processing redesign. [Scaling](https://docs.spring.io/spring-batch/reference/scalability.html). |
| Spring Integration JdbcMetadataStore | Persistent key/value metadata and concurrent conditional replacement | Suitable for integration metadata, but not a substitute for typed admission, field provenance and their canonical transaction. Existing integration dependency does not justify duplicating order authority in this store. [Metadata store](https://docs.spring.io/spring-integration/reference/jdbc/metadata-store.html). |
| JPA optimistic locking | Version-based detection of concurrent entity modification | Detecting a stale version does not choose the winning source. Introducing ORM into an existing JDBC lifecycle engine adds mapping/transaction work without removing the required policy. [Jakarta Persistence](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2). |
| Kafka | Partitioned log, ordering and transactional processing facilities | External infrastructure would add operations and a second durable coordination model. External database effects still require coordination; Kafka ordering would not resolve field policy by itself. No distributed broker requirement is present. [Kafka design](https://kafka.apache.org/41/design/design/). |
| New locks around writers | In-process mutual exclusion | Existing concurrency library already covers this need. Locks do not persist source order across restart or coordinate separate processes. [JDK ReentrantLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html). |

## Custom code that remains necessary

The small pure field-precedence policy, configuration compiler, occurrence
selector and typed admission/provenance ports encode this product's accepted
behavior. Frameworks cannot supply its empty-name, lifecycle and cross-path
priority semantics. SQL adapters implement those ports using existing tools;
they must not grow into a general scheduler, ORM or workflow engine.

Prefer two explicit strategies (legacy preservation and latest registered value)
to a universal rules DSL. Compile policy once and pass immutable decisions through
existing pipeline boundaries. Keep application/domain framework-free even when a
library tutorial places Spring annotations in a service class.

If implementation evidence identifies a concrete missing facility, assess the
candidate's module boundary, license, transitive dependencies, maintenance,
security/update ownership and testability before adding it. No speculative
framework dependency belongs in the initial design.
