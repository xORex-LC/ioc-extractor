# Configuration declaration inspection

[Русская версия](ru/configuration-inspection.md)

The Spring Boot configuration adapter inspects names declared under an explicit
record schema and reports ordered source observations. The service owns settings,
semantic validation, diagnostic codes, output policy and enforcement. Boot owns
binding, conversion and precedence.

The ownership decision is
[ADR 0046](../ADR/0046-reusable-spring-boot-configuration-inspection.md).
The [consumer guide](../guides/libraries/config-spring-boot.md) describes integration;
this document describes the implementation flow and its invariants.

## Runtime flow

```text
prepared schema + stable ordered PropertySources
  -> schema coverage + name-only source traversal
  -> immutable declarations, candidate paths and source coverage
  -> optional migration observations from explicit service descriptors
  -> service-selected strict check / source report
  -> ordinary Boot binding and semantic validation
```

No values are read and no environment or property source is retained in a result.
Source enumeration may itself fail; that operational failure propagates. There
is no durable state, retry, reload, background work or automatic bean scanning.
This name-only guarantee belongs to the inspectors. Source loading, the recipe's
placeholder policy, Binder and semantic validation read values under their own
contracts. A custom source's enumeration callback can also perform arbitrary work;
the inspector does not sandbox it.

## Boundaries

The [restricted-context guide](../guides/libraries/config-spring-boot-check.md) separates
bean selection from source loading. IOC retains effective Boot sources, imports
and profiles; the application-owned collector example accepts one explicit YAML
document plus literal overrides and never invokes ConfigData. Its exact Java
example runs in a separate JVM without IOC classes. Neither policy is imposed
by the shared inspector, which receives already-prepared sources.

`adapters/adapter-config-spring-boot` depends on Spring Boot and Spring Core.
Bootstrap is its current IOC consumer; core and platform modules cannot import
it. The [module reference](../../adapters/adapter-config-spring-boot/README.md)
lists public types, supported shapes and source conventions.

One inspection returns both name outcomes and declaration observations. It does
not run two separate traversals or expose reflection/tokenizer helpers as public
API. `KNOWN` means a supported path, not a valid value. `UNKNOWN`, `AMBIGUOUS`
and `UNSUPPORTED` remain distinct; unsupported coverage cannot justify certainty
that an operator supplied an unknown key.

Coverage describes the schema and the sources actually supplied. An empty source
sequence, an unrelated namespace or only intentional exclusions can yield
`complete()` with no declarations. Required settings and selection of the correct
inputs remain service checks. Aggregate exclusion is keyed by the exact source
name `configurationProperties`, before source-type dispatch; the caller must
reserve that name for Boot's aggregate and supply its concrete sources separately.

## Configuration evolution

`MigrationInspector` consumes the immutable `PropertyInspection` plus explicit
`PropertyMigration` descriptors. It reuses the internal name matcher with a tree
for each old path and accepts only full template matches. Declaring a record or
list container does not imply declarations of its children. This step performs
no source enumeration, value reads or mutation.

For example, a descriptor for `collector.connections[].legacy-timeout` produces
`collector.connections[3].request-timeout` as its replacement hint when index `[3]`
is declared. Removed paths can be recognized without restoring tombstone fields
to the current record schema. The original unknown-name finding is preserved.
Current schema candidates participate in ambiguity checks, including collisions
between a removed flat field and a current nested environment path. Equivalent
record spellings are compared without dashes, preserving dots and indexes.

The [migration API](../../adapters/adapter-config-spring-boot/src/main/java/com/iocextractor/config/boot/MigrationInspector.java)
and [consumer guide](../guides/libraries/config-spring-boot.md#describe-old-names-without-rewriting-configuration)
define template grammar and catalog validation. The observer retains the original
snapshot and safe source references. Compatibility deadlines, diagnostic codes,
severity and value semantics belong to the service. Neither metadata kind nor a
replacement hint changes Boot binding.

`MigrationInspectionTest` uses an unrelated collector model and actual Boot
4.0.8 Binder. A higher-priority new list field can remove a lower-priority old
field from the bound list while both declarations remain observable. Conflict
checks therefore belong after binding, in the service validator. See
[Boot external configuration](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html)
for framework source and aggregate binding rules.

IOC uses the observer for its existing `smb.encrypt` notice, after applying its
own spelling and source-selection policy. It reports concrete endpoint indexes
and keeps earlier custom environment-source behavior. Boolean conversion, dual-key
rejection, rollback compatibility and removed-key failure hints remain local.

An empty declaration snapshot cannot establish namespace identity for a migration
catalog. Choose the catalog from the service schema, rather than treating absence
of observations as proof that the selected catalog or loaded candidate is correct.

## Invariants

1. Source order is preserved; raw names are sorted within each source. Shadowed
   and null-valued declarations remain visible. Results describe declarations,
   not the final value's provenance, particularly for lists.
2. Candidate paths and all result collections are immutable. Ambiguous environment
   segmentation retains every candidate rather than choosing a path.
3. Fixed source categories and encounter ordinals replace arbitrary source labels
   in shared results. Names and map keys can still be sensitive; rendering stays
   service-owned.
4. Boot's aggregate is intentionally excluded. Nonenumerable sources, unqualified
   environment conventions and unsupported schema shapes disclose coverage gaps.
5. IOC retains its existing stricter spellings, converters, startup exceptions,
   legacy migration hints and external-source display selection. Its compatibility
   fallback is local; those restrictions are not universal library rules.

Source ordinals identify encounters within one result. They are not persistent
source identities or a stable key across inspections, restarts or changed source
graphs. Original labels are absent; raw property names and dynamic keys still need
the service's disclosure policy.

## Ownership and execution cost

Calls are synchronous and have no mutable inspector state shared between calls.
The schema tree is rebuilt per inspection; each enumerable source's name array
is copied and sorted, and matching declarations are retained without a sampling
budget. Migration inspection checks the catalog and matches it against the
snapshot. There is no built-in timeout, input quota or cancellation protocol.
Input cardinality, source callback behavior and execution placement belong to the
caller. A small bean context alone does not bound YAML size or enumeration cost.

Keep the prepared source graph stable through inspection and binding when they
must describe one candidate. The returned observations do not freeze the source
graph for Binder or detect a later source mutation. Closing the application's
check context does not close resources merely referenced by arbitrary property
sources; give such resources an explicit owner.

## Failures and extension

Invalid schema roots are programming errors. Expected name problems are result
facts. Enumeration failures propagate without being mislabeled as unknown names.
The caller chooses how either class of failure affects a service or component.

Qualify another schema shape or spelling with both independent name assertions
and the actual Boot Binder before expanding support. Add positive, unknown,
ambiguous and unsupported cases, preserve dynamic key identity and use a source
whose value getter fails to protect the name-only boundary. A new Boot baseline
requires compatibility requalification; no all-version promise is implied.

## Sources of truth

- [PropertyInspector](../../adapters/adapter-config-spring-boot/src/main/java/com/iocextractor/config/boot/PropertyInspector.java)
  and the module's public Javadoc.
- [GenericConsumerTest](../../adapters/adapter-config-spring-boot/src/test/java/com/iocextractor/config/boot/GenericConsumerTest.java)
  for unrelated-schema behavior against the real Binder.
- [Migration contracts](../../adapters/adapter-config-spring-boot/src/test/java/com/iocextractor/config/boot/MigrationInspectionTest.java)
  and [closed-input recipe check](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ConfigurationCheckRecipeIT.java)
  for matching, application policy and context ownership.
- [IOC configuration](configuration.md) for strict service enforcement and command composition.
- [Boundaries](../BOUNDARIES.md) for inward dependency rules.

## When to update

Update this document when schema/source support, coverage meaning, result
ownership or the service enforcement boundary changes.

## Standalone qualification

The `configuration` publication selector builds a resolved POM without the reactor
parent. Its consumer runs inspection/migration and the exact closed-input guide
outside the checkout, using packaged JARs. The application owns Spring Context,
SnakeYAML and its BOM. Default mediation and Boot BOM mediation differ in Commons
Logging (1.3.5/1.3.6) and JSpecify (1.0.0/1.0.1); both graphs are checked explicitly.
Spring dependency downgrades and unavailable managed versions have negative checks.
See the [publication guide](../guides/library-publication.md#configuration-library-admission)
for cold-cache routing, artifact identity and release admission.
