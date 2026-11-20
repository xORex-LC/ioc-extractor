> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0002-output-mapping-and-matching.md) (relative link addresses rebased on relocation).

<a id="0002--конфигурируемое-заполнение-артефактов-и-4-вариантная-классификация"></a>
# 0002 — Configurable artifact filling and four-variant classification

- **Status:** accepted (design); implementation pending
- **Date:** 2026-06-21
- **Related:** [processing](../dev/processing.md),
  [../architecture.md](../ARCHITECTURE.md), [0001-streaming-ingestion.md](0001-streaming-ingestion.md)

<a id="контекст"></a>
## Context

Reference analysis confirmed most inferences but exposed two requirements:

1. **Output artifact filling must not be hardcoded** — it is configurable.
   Clarification: adding a format needs no code if it can be expressed using
   **existing** `ValueProvider`/`Transform` implementations; new column semantics
   require a new thin provider/transform (that is OCP, but it is code).
2. **The authoritative match-code scheme has four variants** (rather than the
   two inferred from the hand-filled reference, which contains only variants 1 and 3).

<a id="решения"></a>
## Decisions

| # | Question | Choice | Rationale | Rejected |
|---|---|---|---|---|
| 1 | Configurable filling | **Providers + transforms**: generic `ConfigurableRowMapper` + `ValueProvider`/`Transform` registries, declarative `columns` | Covers current needs, extensible through new providers/transforms (OCP), no expression parser (KISS) | Expression DSL (complexity/risk); column ordering only (logic would remain in code) |
| 2 | Boundary between variants 1 and 2 | **Public Suffix List** (Guava `InternetDomainName`) | Registrable domain → variant 1, subdomain → variant 2; correct for `com.br`/`co.uk`/`workers.dev` | Counting labels (incorrect for multipart suffixes) |
| 3 | Match-code scheme | **Four variants** (authoritative customer input) | Hand-filled reference is incomplete (only 1 and 3), so cannot serve as an oracle for 2 and 4 | Retain two variants (incorrect) |
| 4 | ID sequence | **Ascending + `auto` = max(lookup)+1** — *applied as a fix* | Reference IDs are contiguous 1..185 ascending; `descending` produced negative IDs | Descending (bug) |
| 5 | Classification logic | **Declarative (rule-based)**: thin evaluator + predicate registry; configuration owns rules and codes | Minimal specific logic in code; change rules without code changes; consistent with filling | Hardcoded triggers in `DefaultMatchPolicy` |

<a id="авторитетная-схема-match-кодов-masks"></a>
## Authoritative match-code scheme (masks)

`url_match | host_match` according to how the value matches the mask:

| # | url_match | host_match | Meaning |
|---|---|---|---|
| 1 | `u:hAS` | `h:dAS` | Domain, all its subdomains and their content |
| 2 | `u:hEX` | `h:dEX` | Third-level domain (exact host) |
| 3 | `u:hEX,dEX` | `null` | Domain with a folder or file, without nested folders |
| 4 | `u:hAS,pEX` | `null` | Scenario with specific parameters |

**Confirmed triggers** (encoded as declarative rules, first-match-wins):
- query present (`?…`) → variant 4;
- otherwise path/port present → variant 3;
- otherwise bare host/IP: registrable domain (PSL) or IP → variant 1; subdomain → variant 2.

<a id="следствия"></a>
## Consequences

- Replace `NetworkMaskRowMapper`/`FileHashRowMapper` with `ConfigurableRowMapper`
  + registries; move `value-case`/`source-strip`/`header` into `columns`/`transform`.
- Extend `MatchPolicy` to four variants; configuration supplies codes/rules.
  Introduce the **domain port `HostClassifier`** (interface), implemented by the
  `PslHostClassifier` adapter (Guava) in `adapter-psl`. The domain contains **no**
  `InternetDomainName`.
- **Guava** is a dependency only in `adapter-psl`, not the domain. The mapper
  receives `url_match`/`host_match` through `match.url`/`match.host` providers over
  the domain `MatchPolicy`.
- **The DSL is bounded** (not an interpreter): only a `from` provider/`const`,
  `when-type` and ordered `transform`; no nested expressions, conditions more
  complex than `when-type` or computations in YAML.
- **Normalization and features are domain services**: `IndicatorNormalizer` +
  `IndicatorFeatureExtractor`; `MatchPolicy`/predicates operate on
  `IndicatorFeatures`, not a URL string inside the mapper.
- Four-variant policy output **will differ from the hand-filled reference** for
  subdomains (variant 2) and parameters (variant 4). This is expected: the rule
  takes precedence over hand-filled data.

<a id="решённые-уточнения-подтверждено"></a>
## Resolved refinements (confirmed)

- **Variant 3 versus 4** when both path and query exist: **query → variant 4** wins.
- **“Without nested folders”** (variant 3) means `dEX` semantics (exact directory),
  not a restriction to a single-segment path.
- **IP addresses:** bare IP → variant 1; IP+port/path → variant 3; IP+query → variant 4.

<a id="открытые-вопросы"></a>
## Open questions

- Enrichment of `score`/`time_*`/`threat_type`/`description` (currently always `NULL`)
  is optional future work.
