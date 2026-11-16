> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0006-design-review-refinements.md) (relative link addresses rebased on relocation).

<a id="0006--уточнения-по-итогам-дизайн-ревью"></a>
# 0006 — Refinements following design review

- **Status:** accepted (design)
- **Date:** 2026-06-21
- **Related:** [ingestion](../dev/ingestion.md), [processing](../dev/processing.md),
  [architecture](../ARCHITECTURE.md),
  [0002-output-mapping-and-matching.md](0002-output-mapping-and-matching.md)

<a id="контекст"></a>
## Context

Customer review identified contradictions/underspecified aspects in the design
documents. The accepted resolutions follow (documents updated).

<a id="решения"></a>
## Decisions

| # | Finding | Resolution |
|---|---|---|
| 1 | `SourceFeed` described as both driven and driving | `adapter/in/watch` is an inbound adapter calling the **driving port `IngestSourceUseCase`**; `SourceFeed` is an **adapter-local** abstraction over SI, **not** a core port |
| 2 | PSL/Guava leaked into the domain | Domain contains the **`HostClassifier` port**; the `PslHostClassifier` adapter (Guava) lives in `adapter-psl`. Domain has **no** `InternetDomainName` |
| 3 | Ingestion idempotency required a strict state machine | Explicit unit states in `IngestionLedger`: `CLAIMED → PARTITION_WRITTEN → LEDGER_RECORDED → SOURCE_ARCHIVED → (AGGREGATED)` + startup **compensations** based on the last state |
| 4 | Global IDs in the aggregator were underspecified | Durable **`dedupKey → id`** index (in canonical storage or a separate aggregator index): a known key retains its ID across reaggregation/order changes/partition deletion/dedup; a new key gets `max+1` |
| 5 | “New format without code” overpromised | Clarified: a format using **existing** `ValueProvider`/`Transform` needs no code; new column semantics require a new thin provider/transform (code) |
| 6 | Whole-file and tail shared one idempotency model | Separated: whole-file uses **content-hash**; tail uses a **checkpoint** (file identity + offset + rotation marker + record ID/hash) |
| 7 | Risk of `ConfigurableRowMapper` becoming an interpreter | The DSL is **bounded**: `from`/`const`, `when-type`, ordered `transform`; no nested expressions, conditions more complex than `when-type` or computations in YAML |
| 8 | `MatchPolicy` built on URL-string parsing in the mapper | Introduced domain `IndicatorNormalizer` + `IndicatorFeatureExtractor`; `MatchPolicy`/predicates operate on `IndicatorFeatures`; PSL feature through the `HostClassifier` port |

<a id="следствия"></a>
## Consequences

- New/refined ports: `IngestSourceUseCase` (in), `HostClassifier` (domain port →
  `adapter-psl`); new domain services `IndicatorNormalizer`,
  `IndicatorFeatureExtractor` (current map in [ARCHITECTURE.md](../ARCHITECTURE.md)).
- `IngestionLedger` stores **states**, not a boolean; recovery uses compensations.
- The aggregator owns durable `dedupKey → id`.
- The filling DSL is established as bounded (not a language).

<a id="открытые-вопросы"></a>
## Open questions

- Exact `dedupKey` form (normalized value + type) and ID-index location (inside
  canonical storage versus separate file), during aggregator implementation.
- Tail checkpoint format (inode versus filesystem-specific file ID), during tail-mode implementation.
