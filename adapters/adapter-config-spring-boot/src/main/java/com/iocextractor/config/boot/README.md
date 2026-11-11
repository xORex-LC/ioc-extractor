# com.iocextractor.config.boot

## Purpose and boundary

Record-schema and declaration inspection without property-value access.
The public API returns immutable observations; service code decides what they
mean for binding, reporting and startup. Follow the [module dependency rules](../../../../../../../README.md).
Exact argument, result and exception contracts live in Javadoc.

## Structure

| File | Responsibility |
|---|---|
| [PropertySchema.java](PropertySchema.java) | Canonical single namespace segment and record root |
| [PropertyInspector.java](PropertyInspector.java) | Source traversal, enumeration order and coverage classification |
| [PropertyInspection.java](PropertyInspection.java) | Declarations, matching statuses, source references, coverage and schema gaps |
| [PropertyMigration.java](PropertyMigration.java) | Service-owned deprecated/removed path and optional replacement descriptor |
| [MigrationInspector.java](MigrationInspector.java) | Catalog validation and old-name matching against an existing snapshot |
| [MigrationInspection.java](MigrationInspection.java) | Original snapshot, indexed matches and resolution model |
| [SchemaModel.java](SchemaModel.java) | Internal record/container reflection tree and unsupported-shape gaps |
| [NameMatcher.java](NameMatcher.java) | Internal relaxed-name segmentation, candidate retention and ambiguity |
| [MigrationPath.java](MigrationPath.java) | Internal canonical template grammar, index preservation and replacement hints |
| [package-info.java](package-info.java) | Shared package contract |

Only the first six top-level types are public. The last three helpers are
package-private implementation details; do not turn them into an alternative
public validation API.

## Inspection flow and source ownership

1. The caller prepares the source graph and supplies its order, highest priority
   first. `PropertySchema` checks the namespace/root contract.
2. `SchemaModel` reflects the schema per call. `PropertyInspector` expands
   composite children, copies/sorts each enumerable source's name array and
   retains declarations in source order, then raw-name order.
3. `NameMatcher` keeps all candidate canonical paths. Status is `KNOWN`,
   `UNKNOWN`, `AMBIGUOUS` or `UNSUPPORTED`; it never chooses an ambiguous winner.
4. Optional `MigrationInspector` consumes the snapshot and a validated catalog,
   producing old-name observations without visiting the property sources again.

The exact source name `configurationProperties` is reserved for Boot's attached
aggregate and excluded before type dispatch. Supply its concrete sources
separately. Nonenumerable sources disclose incomplete coverage. Qualified
`SystemEnvironmentPropertySource` names are `systemEnvironment` or names ending
in `-systemEnvironment`; other environment-source conventions remain unsupported.

Results contain encounter ordinals and fixed source kinds, not arbitrary source
labels, source objects or environments. Ordinals are local to one result, not
persistent identity. Raw names and dynamic map keys can still carry sensitive
text. A name-only result is not automatically safe to log verbatim.

## Schema and result boundaries

Qualified shapes are ordinary nested records with ASCII lower camel case fields,
the scalar types listed by `SchemaModel`, resolvable `List` elements (records or
scalars), and flat scalar `Map` values with String/enum keys. Schema gaps disclose
unsupported shapes even with no input declarations.

Aliases via `@Name`, unqualified field names, recursive/unresolved schemas,
JavaBeans, sets, arrays, nested maps and custom conversion shapes are outside the
qualified model. Scalar assignment to a record or an entire record list cannot
be established from reflection. Dynamic map keys retain their own identity;
they do not inherit record-field spelling equivalence. Full grammar and supported
spellings belong to the [consumer guide](../../../../../../../../../docs/guides/libraries/config-spring-boot.md).

`KNOWN` means a supported path, not a convertible or acceptable value. Unknown
and ambiguous declarations can coexist with `complete() == true`; unsupported
source/name/schema coverage makes it false. Empty sources or an unrelated
namespace can produce complete coverage with no declarations. Services still
check that they selected the right inputs and supplied required settings.

Inspection retains lower-priority and null-valued declarations. It does not
read values, select effective overrides, validate enum-map keys or prove list
index continuity. Binder owns actual conversion and whole-list precedence.

## Migration semantics

Descriptors name full canonical old paths, optionally containing `[]` numeric
list placeholders, and a replacement preserving namespace/index count.
`DEPRECATED` and `REMOVED` describe service policy; neither installs a Binder
alias or rewrites configuration. Removed paths need not exist in today's schema,
and their original unknown-name findings remain unchanged.

Catalog validation rejects overlapping old paths and migration chains. Matches
copy concrete indexes into replacement hints; declaring a container alone does
not imply declarations of its children. Current schema candidates participate in
ambiguity checks, and an unsupported original observation stays unsupported.
`MigrationInspection.complete()` delegates to original coverage, independently
of whether every migration observation is resolved.

## Failures, lifecycle and extension

Calls are synchronous with no shared mutable inspector state. Results copy their
collections and preserve candidate order, but do not freeze the caller's live
sources for later binding. Keep sources stable across inspection/binding when
both must describe the same configuration candidate.

Invalid roots/catalogs are argument errors; unsupported shapes/names are result
facts. Enumeration failures propagate, without a successful partial result.
Source callbacks are not sandboxed and may do I/O or throw arbitrary exceptions.
There is no sampling quota, timeout, cancellation or source-resource lifecycle
owned here. Callers bound input size, choose execution placement and close owned
resources. Name enumeration is copied/sorted; retained declarations and per-call
schema compilation have real memory/CPU cost.

Before expanding a shape, naming convention or Boot baseline, extend the
[local contracts](../../../../../../test/java/com/iocextractor/config/boot/README.md) with independent name assertions,
actual Binder counterexamples and a value getter that fails if called. Preserve
coverage honesty, immutable results and service-owned enforcement. IOC-specific
policy remains in [bootstrap integration](../../../../../../../../../docs/dev/configuration-inspection.md).
