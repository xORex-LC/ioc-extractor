# ioc-config-spring-boot

Property-name inspection and ordered declaration observations for a service-owned
Spring Boot record schema. Requires Java 21. The module is implemented in the
reactor; publication and repository-consumer qualification are separate work.

**Layer rule:** a Spring integration adapter, consumed explicitly by bootstrap.
Main dependencies are Spring Boot and Spring Core; no starter, auto-configuration,
IOC model, diagnostics, logging provider or application port is required.

## Public contract

- `PropertySchema` selects one canonical prefix and an ordinary record root.
- `PropertyInspector.inspect(schema, sources)` performs one value-free traversal
  of stable sources in precedence order. Both name findings and declaration
  observations come from this same traversal.
- `PropertyInspection` copies its declarations, source coverage and schema gaps.
  Nested records/enums describe `KNOWN`, `UNKNOWN`, `AMBIGUOUS` and `UNSUPPORTED`
  outcomes. A safe source identity contains only an encounter ordinal and fixed
  category, never a raw source name or source object.

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

## IOC integration

IOC delegates source/name inspection to this module, retaining its strict
spelling and environment policy in bootstrap. Custom IOC conversions and
unsupported generic shapes retain the local compatibility check. The override
report keeps its existing baseline selection, labels and first-external-declaration
policy; generic recognition does not silently widen the service contract.
IOC also retains its enumeration failure for a composite with opaque children;
the shared inspector itself reports incomplete coverage for those children.

## Verification and extension

`PropertyInspectionTest` covers matching, ambiguity, source order, immutable
results, coverage and the no-value-read boundary. `PropertySchemaTest` covers
supported and unsupported record shapes. `GenericConsumerTest` uses an unrelated
collector schema and the real Boot Binder, including conversion failures and
list replacement. These tests do not qualify a published consumer or another
Boot version. See [configuration inspection](../../docs/dev/configuration-inspection.md)
for the cross-module flow and extension rules.
