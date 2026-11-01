# ADR 0048: Independent library versions and reuse before duplication

Status: Accepted, 2026-11-01. Independent version implementation is pending.

## Context

Six reusable artifacts now have published releases and paired English/Russian
guides. They remain in the IOC monorepo, and their source POMs still inherit the
product's `${revision}`. The publication admission also requires a component
version to match the selected product release branch.

The shared version was a deliberate first-publication simplification in
[ADR 0029](0029-release-branch-library-publication.md). Future services should
be able to adopt library fixes without following IOC's release cadence. Shared
code also needs an explicit development rule: use an existing suitable API and
extend its owning library when a real shared capability is missing. Publication
does not freeze development, and a local copy would create another implementation
to maintain.

## Decision

### Release ownership

Adopt independent library release versions. This supersedes the product/library
lockstep policy in ADR 0029 and the corresponding version clauses in
[ADR 0044](0044-reusable-diagnostics-contract.md),
[ADR 0045](0045-reusable-slf4j-logging-contract.md),
[ADR 0046](0046-reusable-spring-boot-configuration-inspection.md) and
[ADR 0047](0047-explicit-spring-control-event-adapter.md), subject to the
implementation gate below. Their API and ownership decisions remain unchanged.

Concurrency, diagnostics, logging and configuration each have their own release
version. Events core and its Spring adapter initially form one release family:
both use the same version and the adapter requires that exact core version.
They retain separate artifacts, component tags, signed bundles and repository
qualification. Core is published and qualified before the adapter.
Releasing either member advances the family version and releases both, including
an unchanged core when only the adapter implementation changes.

IOC's application and internal modules keep the product version. A product
release does not require a new version of an unchanged library. Each consuming
service pins a chosen released library version and owns its upgrade timing.
The libraries stay in this repository; separate repositories, branches per
library and a public BOM are not required by this decision.

The maintained [release policy](../RELEASE-PROCESS.md#reusable-library-versions)
owns version increments, compatibility, immutable releases and activation
criteria. Existing `0.3.0-rc.1` coordinates, tags and signed bundles stay immutable.

### Development and reuse

Before introducing a mechanism covered by the
[library catalog](../guides/libraries/README.md), inspect the relevant guide,
module reference, Javadoc, implementation, tests and existing call sites.
Use the existing API when it meets the requirement. If it does not, explain the
missing behavior and choose its owner by responsibility and dependency direction.

A focused generic fix or extension belongs in the owning library, with contract
review, regression tests and documentation. A second deployed consumer is not a
prerequisite for correcting or extending an already admitted library. Service
catalogs, schemas, business rules, wiring and recovery policy remain in services.
Do not broaden an API for hypothetical uses or force a framework dependency into
a framework-free layer merely to reuse code.

Library and feature changes may be developed together in one branch or PR, using
development versions. A separate library PR is useful when the contract needs
independent review, but is not mandatory for every change. Publish and qualify
the required library release before releasing the consuming service; consumer
development can continue before publication. Code changes alone do not authorize
signing, pushing tags, publishing artifacts or releasing the service.

### Implementation gate

This decision approves the policy, not a completed Maven or CI migration.
The current reactor and protected publication admission remain operationally
lockstep until the activation criteria in the release policy pass. In particular,
the existing `-Drevision` override cannot independently version one library, and
an arbitrary component version must not be forced through the current
release-branch check.

The implementation must separate library versions from the product version in
POMs, dependency management, publication identity and consumer fixtures. It must
also prove that a service release uses the selected published library content,
including when reactor resolution would otherwise choose local source. Keep
component source/tag provenance and recovery checks intact while changing the
branch/version relationship. The remaining implementation is tracked as
[TOOL-9](../KNOWN-ISSUES.md).

## Alternatives and consequences

- Permanent product/library lockstep is simpler, but couples unrelated service
  and library release schedules.
- Moving each library to another repository would provide physical separation,
  but adds coordination overhead without being necessary for independent versions.
- Releasing a library before any consumer development would isolate changes,
  but delays the integration feedback needed to validate the API.
- Independent versions for the two events artifacts would require a separate
  compatibility matrix; the exact-version family retains today's dependency rule.

Independent versions add version-selection and release-evidence work. Reuse
reduces duplicate mechanisms but does not justify moving service policy into a
library. Source/binary API comparison remains open in TOOL-6; until automation is
adopted, contract review and behavioral tests must state their coverage honestly.

## Validation

The [development guide](../guides/libraries/development.md) and its
[Russian counterpart](../guides/ru/libraries/development.md) describe the same
workflow. The release policy distinguishes the accepted target from current
POM/workflow behavior. No existing publication is recreated by this documentation
change. Technical activation requires the recorded TOOL-9 evidence.
