# com.iocextractor.domain.model

## Responsibility and boundary

Shared IOC records and enums, with no dependency on other domain capabilities or
infrastructure. These values describe semantics; they do not allocate canonical
IDs, export slots or persistent row keys.

## Types

| Source | Responsibility |
|---|---|
| [Indicator](Indicator.java) | Value, type and attributed source; extraction deduplication key |
| [IndicatorType](IndicatorType.java) | Six supported IOC types, categories and aligned STIX vocabulary |
| [IndicatorCategory](IndicatorCategory.java) | `NETWORK` or `FILE` family |
| [SourceContext](SourceContext.java) | Nullable source label and optional section; label replacement preserves section |
| [MaskMatch](MaskMatch.java) | Nullable `urlMatch` / `hostMatch` policy codes |

## Key contracts

`Indicator` requires non-null value, type and source. Construction does not refang,
normalize or validate the lexical IOC. Record equality includes all components;
`dedupKey()` is exactly `type.name() + '|' + value`, excludes provenance and preserves
value case. The caller owns normalization and which duplicate wins. This key is
not the canonical artifact identity formula.

`IPV4`, `DOMAIN` and `URL` belong to `NETWORK`; `MD5`, `SHA1` and `SHA256` belong to
`FILE`. STIX strings are metadata for potential integration, not an implemented
STIX export format or the internal model.

No preceding section marker is represented by `new SourceContext(null, null)`;
there is no `UNKNOWN` singleton. `MaskMatch` stores supplied strings without
interpreting them; null codes represent absent values. CSV rendering belongs to
adapters. See [local model tests](../../../../../../test/java/com/iocextractor/domain/model/DomainModelInvariantTest.java)
for vocabulary, deduplication and source-label invariants.
