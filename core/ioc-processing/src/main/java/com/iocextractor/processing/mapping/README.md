# com.iocextractor.processing.mapping

## Responsibility and boundary

The shared declarative scalar field evaluator for document and processed-import
preparation. It consumes already classified views and supplied registries; it does
not execute a route, choose views, classify again, encode CSV or perform canonical
writes. Allowed collaborators are the local model, domain values/bare-IP rule and
platform errors.

## Evaluator and contracts

| Source | Responsibility |
|---|---|
| [RowMapper](RowMapper.java) | Ordered header/cells and optional deferred public-ID column |
| [ConfigurableRowMapper](ConfigurableRowMapper.java) | One column evaluator, bound transform specs and source-label presentation |
| [ColumnSpec](ColumnSpec.java) | Provider/constant, type/condition gates and ordered transforms |
| [ValueProvider](ValueProvider.java), [Transform](Transform.java) | Registered value computation and transformation SPI |
| [ArtifactFilter](ArtifactFilter.java) | All include predicates and no matching exclude predicate |
| [MappingValueException](MappingValueException.java) | Expected input-dependent provider/transform rejection |
| [RowMappingException](RowMappingException.java) | Located rejection with column, component kind/name and cause |

`ColumnSpec` copies its optional lists; the mapper copies columns and registries.
Registry objects are still shared collaborators. Construction is not complete
schema/registry validation: callers admit names, output-column uniqueness, view
bindings and supported declarations before use. The mapper has runtime guards,
not an expression language or a replacement configuration validator.

## Column evaluation and views

Headers and returned cells retain declaration order; null is an absent cell for
the outer encoder. `toRow(defaultView, columnViews)` selects one supplied view by
column name, falling back to the default. Type gates, named conditions and provider
all inspect that same classified value. Transforms operate on its resulting scalar.
The caller owns view resolution, non-null consistent values and binding admission.

Evaluation first checks optional `whenType`, optional `whenTypes` membership and
all named conditions, short-circuiting on rejection. If both type forms are supplied,
both apply; an empty type list accepts none. A failed gate yields null without
calling a provider or transform. An admitted cell uses the `const` literal or its
named provider, then applies transforms in declaration order when the starting
value is non-null. Mapping does not short-circuit the remaining chain if a custom
transform subsequently returns null; custom chains must handle their own outputs.

Transform specs bind once per distinct string at construction, splitting at the
first colon. No argument, an empty argument and an argument containing further
colons remain distinct. An unresolved transform remains a lazy failure until a
non-null reached cell invokes it. Missing providers/conditions likewise fail when
reached; bootstrap still preflights operator declarations eagerly. Providers run
per occurrence, regardless of semantic-session cache hits.

`idColumn()` reports the first column sourced from `id`; it does not reserve or
render an identifier. Composition registers the deferred provider, and the outer
write boundary fills the ID cell after preparation acceptance.
`mapSourceLabel` applies only a declared `source.label` column's presentation
transforms to an already admitted label. It bypasses the ordinary provider/gates
and rejects an unknown or differently sourced column; source authority stays outside.

## Built-in components

| Registered key / source | Behavior |
|---|---|
| `value` — [IndicatorValueProvider](IndicatorValueProvider.java) | Supplied IOC value |
| `source.label` — [SourceLabelValueProvider](SourceLabelValueProvider.java) | Nullable provenance label |
| `match.url` / `match.host` — [MatchUrlValueProvider](MatchUrlValueProvider.java), [MatchHostValueProvider](MatchHostValueProvider.java) | Materialized mask codes, with no reclassification |
| `address.ip` / `address.url` — [AddressIpValueProvider](AddressIpValueProvider.java), [AddressUrlValueProvider](AddressUrlValueProvider.java) | Bare IPv4 / complementary non-bare value |
| `lower` / `upper` — [LowercaseTransform](LowercaseTransform.java), [UppercaseTransform](UppercaseTransform.java) | Locale-independent whole-value casing |
| `lower-host` — [LowerHostTransform](LowerHostTransform.java) | Lowercase scheme/authority, preserving path/query/fragment spelling |
| `strip-prefix` — [StripPrefixTransform](StripPrefixTransform.java) | Remove one matching leading argument literally |

Registration is supplied by the caller. Address providers use the domain bare-IP
predicate; they do not independently restrict input to network categories.
Artifact/type gates own that restriction. `lower-host` is a presentation transform,
not a network-address validator. Artifact filters are separate from column gates
and type routing: `none()` accepts all, include is AND, exclude is any-match veto.

## Failures and verification

Only `MappingValueException` thrown by a provider or transform is translated to
`RowMappingException`. Unknown registry names use `IocExtractorException`; other
programming failures propagate. The CSV preparer converts located rejections to
element diagnostics; this module itself chooses no continuation/failure policy.
Messages and causes of expected failures must be safe for operator diagnostics
and contain no raw input. Constructors check a nonblank reason, not redaction;
the extension producer owns that content.

See [mapping tests](../../../../../../test/java/com/iocextractor/processing/mapping/README.md),
the [CSV preparer](../../../../../../../../../adapters/adapter-csv/src/main/java/com/iocextractor/adapter/out/sink/csv/README.md)
and [processing](../../../../../../../../../docs/dev/processing.md) for local evidence and integration flow.
