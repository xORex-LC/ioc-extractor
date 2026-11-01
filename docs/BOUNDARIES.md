# Architecture boundaries

[Русская версия](ru/BOUNDARIES.md)

The dependency rules in [ARCHITECTURE.md](ARCHITECTURE.md) are protected by
compilation, Maven Enforcer and architecture tests. A protected boundary has an
executable failure when it is crossed. Review explains ownership and intent;
the build enforces the rules listed below.

## Dependency direction

- Domain code stays independent of application, adapters, bootstrap and frameworks.
- Processing composes IOC behavior without integration frameworks. Its
  [POM](../core/ioc-processing/pom.xml) bans the configured framework dependencies.
- Application code depends inward on domain, processing and framework-free
  platform contracts. It does not import adapters, bootstrap, Spring or logging.
- Parsing, CSV, JDBC, SMB and Spring integration belong to their adapters and
  bootstrap. Their implementation types must not cross inward-facing ports.
- Services own catalogs, schemas, business rules, durable state and recovery.
  Reusable libraries own their documented technical contracts.

The [module map](MODULARIZATION.md) describes module responsibilities. Exact
dependency coordinates and versions belong to the root and module POMs.

## Executable architecture rules

The main cross-module rules are in
[ArchitectureTest](../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java).
The table identifies the enforced boundary; the test source remains authoritative
for the exact package selection and exceptions.

| Boundary | Enforcement |
|---|---|
| Layers point inward; top-level packages and individual platform subsystems have no dependency cycles | `layers_point_inward`, `no_package_cycles` |
| Domain excludes frameworks, logging, application pipeline and ingestion concerns | Domain rules in `ArchitectureTest` and [DomainBoundaryTest](../core/ioc-domain/src/test/java/com/iocextractor/domain/DomainBoundaryTest.java); domain POM bans |
| Application excludes adapters, bootstrap, Spring and logging | `application_depends_only_inward`, `application_pipeline_is_framework_and_adapter_free`; Maven dependencies |
| Generic diagnostics depend only on their own types and the JDK; IOC metadata, exceptions and delivery integration stay local | `reusable_diagnostics_depends_only_on_itself_and_jdk`, `diagnostics_core_is_framework_and_adapter_free`; [ADR 0044](ADR/0044-reusable-diagnostics-contract.md) |
| Reusable logging depends only on its own types, the JDK and SLF4J | `reusable_logging_depends_only_on_itself_jdk_and_slf4j`, module-local [LoggingArchitectureTest](../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LoggingArchitectureTest.java); [ADR 0045](ADR/0045-reusable-slf4j-logging-contract.md) |
| Configuration inspection stays outside framework-free layers; its package belongs to the adapter layer | `reusable_configuration_depends_only_on_itself_jdk_and_spring`, `configuration_inspection_stays_outside_framework_free_layers`; [ADR 0046](ADR/0046-reusable-spring-boot-configuration-inspection.md) |
| The ETL kernel excludes IOC domain, application, adapters, logging and frameworks | `platform_etl_is_generic_and_framework_free` |
| Event contracts remain JDK-only and exclude broker, serialization and keyed-execution mechanics | `platform_events_is_framework_free_and_not_a_broker`, `control_events_are_not_serializable`, `keyed_execution_is_not_part_of_platform_events` |
| The Spring event adapter depends only on its own types, JDK, event contracts, Spring and SLF4J | `events_spring_depends_only_on_contracts_jdk_spring_and_slf4j`; [ADR 0047](ADR/0047-explicit-spring-control-event-adapter.md) |
| Concurrency excludes events, frameworks and business layers | `platform_concurrency_is_framework_free_and_event_free` |
| JDBC, HikariCP, SQLite and SMBJ types stay outside inner layers | Storage and transport rules in `ArchitectureTest`; application POM bans |
| Managed-import core excludes CSV, JDBC, SMBJ, Spring and concrete pipeline stages | `dataframe_import_core_is_framework_and_integration_free` |
| Snapshot readers and slice writers retain streaming boundaries | `streaming_snapshot_reader_does_not_materialize_canonical_artifacts`, `slice_writer_owns_no_jdbc_or_materialized_repository_model` |
| Jackson manifest mapping stays within its selected adapter, CLI and bootstrap packages | `jackson_manifest_mapping_stays_in_its_adapter` |
| Adapters do not own concrete pipeline stages; stages do not own pipeline order | Pipeline rules in `ArchitectureTest` and [PipelineModelArchitectureTest](../core/ioc-application/src/test/java/com/iocextractor/application/pipeline/PipelineModelArchitectureTest.java) |
| Application ports are interfaces, with command/result contract exceptions | `ports_are_interfaces` |
| Domain capability packages retain their internal DAG | `DomainBoundaryTest` |

ArchUnit checks production classes through the normal JUnit lifecycle. Module-local
tests add narrower rules. These checks do not imply that every package or every
public API has completed a manual architecture review.

## Maven and source boundaries

The reactor graph controls which project classes are available during compilation.
Parent Enforcer rules require the supported Java/Maven versions and reject duplicate
POM dependency declarations. Domain, processing and application POMs add their
own dependency bans; inspect those POMs for the exact lists.

Constructor injection keeps framework wiring at the composition root. Narrow ports,
deliberate public visibility and package-private implementation details support the
dependency rule. Java visibility remains a design responsibility where no dedicated
test constrains it.

`dependencyConvergence`, JPMS descriptors and Spring Modulith/canvas enforcement
remain deferred. A successful reactor build does not imply adoption of those checks.

## Analyzer scope and report gates

Build-quality checks have distinct enforcement policies:

| Control | Command/lifecycle | What fails the gate |
|---|---|---|
| Scope/configuration integrity | Root `validate`, before child builds | A reactor project without an explicit disposition, scope/dependency/ruleset drift or invalid configuration |
| SpotBugs | Maven `verify` | New, stale, moved or metadata-drifted raw findings against exact reviewed identities; analyzer and report-integrity errors |
| PMD CPD | Maven `verify` | Any change in the reviewed duplication-group count, plus analyzer, source-scope or report-integrity errors |
| Adopted PMD source policy | Separate `make pmd-analysis` and CI job | A finding in a zero-baseline rule, any advisory per-rule count drift, or analyzer/scope/report errors |
| PMD ownership/size watchlist | Explicit `make pmd-watchlist` | Analyzer/scope/report errors; findings remain advisory |
| Maven dependency analysis | Opt-in `make dependency-analysis` or Maven profile | Tool execution errors; dependency findings remain advisory (`failOnWarning=false`) |

CPD and PMD count ratchets require semantic review. An unchanged count can hide a
replacement finding; passing the gate is not a statement that there are no findings.
Changing a reviewed count or SpotBugs acceptance requires the procedure in
[build-quality.md](dev/build-quality.md).

The current analyzed universe is defined by the checked-in scope registries:

- [SpotBugs](../build-support/spotbugs-report/spotbugs-scope.tsv);
- [CPD](../build-support/cpd-report/cpd-scope.tsv);
- [PMD](../build-support/pmd-report/pmd-scope.tsv).

Each registry accounts for the complete reactor. Production code is explicitly
selected; the test-support TCK, tests, generated/vendor code and build-only POMs
are excluded from production analysis. The
[BuildQualityVerifier](../build-support/build-quality/BuildQualityVerifier.java)
checks registry agreement with reactor topology and report-module dependencies.
Its synthetic-reactor tests exercise positive and negative cases.

Reports are checked after analysis: required XML/HTML must exist and be structurally
valid, source paths must stay in scope, and analyzer failures cannot become an empty
successful report. SpotBugs raw and filtered views retain distinct purposes.
CPD uses one repository-wide source analysis; PMD policy and watchlist use separate
report directories and exact rulesets.

Dependency hygiene has its own scope: the opt-in profile inspects functional JARs,
including the TCK; POM projects are skipped by the plugin. Unlike the analyzer
registries, its findings do not enforce a reviewed production allowlist. Framework,
SPI and test-aggregate noise retains explicit dispositions.

## Verification and maintenance

Use the root [Makefile](../Makefile) for the documented commands. `make verify`
includes architecture tests, Enforcer, SpotBugs and CPD. PMD source policy is a
separate gate; it is not implied by a successful `verify`. Test lifecycle and
coverage rules belong to [TESTING.md](TESTING.md).

When a boundary, analyzer scope or enforcement policy changes, update its executable
rule, affected module references and this map in the same change. Add an ADR when
the ownership decision changes. A boundary is complete when a representative
violation fails its owning check and the affected documentation describes that check.
