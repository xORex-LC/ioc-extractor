# ioc-config-spring-boot

Property-name inspection and ordered declaration observations for a service-owned
Spring Boot record schema. Requires Java 21. The module is implemented in the
reactor; publication and repository-consumer qualification are separate work.

**Layer rule:** a Spring integration adapter, consumed explicitly by bootstrap.
Main dependencies are Spring Boot and Spring Core; no starter, auto-configuration,
IOC model, diagnostics, logging provider or application port is required.
Boot transitively brings Spring Context and its dependencies. The parent manages
versions; standalone POM/dependency mediation is a separate publication gate.
The ownership decision is [ADR 0046](../../docs/ADR/0046-reusable-spring-boot-configuration-inspection.md).

## Public contract

- `PropertySchema` selects one canonical prefix and an ordinary record root.
- `PropertyInspector.inspect(schema, sources)` performs one value-free traversal
  of stable sources in precedence order. Both name findings and declaration
  observations come from this same traversal.
- `PropertyInspection` copies its declarations, source coverage and schema gaps.
  Nested records/enums describe `KNOWN`, `UNKNOWN`, `AMBIGUOUS` and `UNSUPPORTED`
  outcomes. A safe source identity contains only an encounter ordinal and fixed
  category, never a raw source name or source object.
- `PropertyMigration` describes an accepted deprecated or removed name, with an
  optional replacement. It does not register an alias or a value conversion.
- `MigrationInspector.inspect(inspection, migrations)` matches those descriptors
  against the existing snapshot. `MigrationInspection` retains original coverage
  and reports resolved, ambiguous or unsupported observations.

The library reads names, not values. Unknown and ambiguous names are data;
services select severity, render messages and decide whether to start. Operational
source-enumeration failures propagate with their original identity. Results
retain lower-priority and null-valued declarations and do not establish final
Binder provenance. Even a property name or dynamic key may contain sensitive
text: callers still own their rendering policy.

## Supported shapes

Qualified against the reactor's Boot 4.0.8 baseline: nested records, indexed
lists of records/scalars, whole scalar lists, ordinary scalar types and flat
String/enum-keyed scalar maps with single-token keys. Canonical/camel/dashless
field spellings and compact/split field words in qualified environment sources
are recognized. Dynamic map keys retain their own identity.

Fields use ASCII lower camel case. Aliases, acronym or other field naming,
recursive/unresolved types, JavaBeans, sets/arrays,
nested maps and custom conversions disclose unsupported schema or declaration
coverage. A record declared as a scalar and a whole scalar record-list assignment
are unsupported. Dotted map-key grammar is outside this initial contract.
Binder still decides conversion, index continuity and aggregate binding.

A `SystemEnvironmentPropertySource` must use `systemEnvironment` or a name
ending in `-systemEnvironment` for the qualified environment convention. Other
names disclose incomplete coverage. Composite children are inspected separately;
nonenumerable sources are incomplete. The attached `configurationProperties`
aggregate is deliberately excluded to avoid duplicate observations.

## Migration observations

Descriptors use canonical lowercase record paths, for example
`collector.connections[].legacy-timeout` with replacement
`collector.connections[].request-timeout`. An observation for index `[3]` carries
that index in both paths. Multiple list indexes are copied in order; the
replacement must have the same number of `[]` placeholders. Fixed paths and
removed names absent from the current record schema also work. A replacement is
a name hint, with no promise that the old value is valid for the new setting.

All descriptors belong to one namespace, matching the supplied inspection.
Invalid templates, equivalent/overlapping old paths and migration chains are
rejected before matching. Templates do not accept literal indexes, wildcards or
regex callbacks. Services must describe record fields; a dynamic map entry is
not a supported migration target. Descriptors do not validate a historical model
or the replacement's bindability. Declarations from a different namespace are
rejected; an empty snapshot cannot establish namespace identity, so the caller
must select the matching catalog.

Results retain shadowed and null-valued declarations. An old and new name in
different sources do not prove a conflict between bound fields: Binder can
replace an entire list. Migration observations never change the original name
status: an unknown removed name stays unknown. Competing current
schema paths make an observation ambiguous; unsupported schema/source conventions
remain unsupported. A resolved observation in a partial inspection does not prove
the absence of old names in opaque sources. No source is read again or modified.

The service owns warning/error policy, migration windows, value conversion,
bound-field conflict checks and recovery scope. The library performs no rewrite,
startup check or metadata generation.

## IOC integration

IOC delegates source/name inspection to this module, retaining its strict
spelling and environment policy in bootstrap. Custom IOC conversions and
unsupported generic shapes retain the local compatibility check. The override
report keeps its existing baseline selection, labels and first-external-declaration
policy; generic recognition does not silently widen the service contract.
IOC also retains its enumeration failure for a composite with opaque children;
the shared inspector itself reports incomplete coverage for those children.
IOC's accepted-alias notices now use the same inspection snapshot and carry
concrete endpoint indexes. The local naming policy still controls whether to
emit each hint, including older custom environment-source conventions that the
generic inspector leaves unqualified. Removed-key failure hints remain IOC-owned.

## Verification and extension

Start with the [consumer guide](../../docs/guides/configuration-library.md) for
source selection, coverage handling, migration observations and service policy.
For application-owned binding and semantic validation, use the
[restricted-context recipe](../../docs/guides/configuration-library-check.md).
It defines explicit YAML/override inputs and import/profile rejection, while IOC
keeps ordinary effective Boot loading. `ConfigurationCheckRecipeIT` compiles the
exact guide without IOC classes; this adds no runner, YAML loader, starter or
new public type to this JAR. Cold repository consumption remains unqualified.

`PropertyInspectionTest` covers matching, ambiguity, source order, immutable
results, coverage and the no-value-read boundary. `PropertySchemaTest` covers
supported and unsupported record shapes. `GenericConsumerTest` uses an unrelated
collector schema and the real Boot Binder, including conversion failures and
list replacement. These tests do not qualify a published consumer or another
Boot version. See [configuration inspection](../../docs/dev/configuration-inspection.md)
for the cross-module flow and extension rules.

`MigrationInspectionTest` qualifies deprecated/removed names, indexed replacement
hints, relaxed and environment spellings, source coverage and ambiguity. Its real
Binder counterexample demonstrates why declarations cannot establish bound-value
conflicts. Test dependencies and the runtime dependency graph are unchanged.
