> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0004-extraction-and-test-corpus.md) (relative link addresses rebased on relocation).

<a id="0004--извлечение-паттерны-и-тест-корпус"></a>
# 0004 — Extraction, patterns and test corpus

- **Status:** accepted (design); implementation pending
- **Date:** 2026-06-21
- **Related:** [processing](../dev/processing.md), [conventions](../CONVENTIONS.md)

<a id="контекст"></a>
## Context

Refining extraction against the real corpus and test coverage. Analysis showed
that the particular source is basic, while the rich corpus is in the reference
(185 masks); the authoritative four-variant rule reclassifies approximately 20%
of reference rows.

<a id="решения"></a>
## Decisions

| # | Question | Choice | Rationale | Rejected |
|---|---|---|---|---|
| 1 | Type coverage now | **Network + .onion + telegram** | Present in the corpus; no email/IPv6 in the data (YAGNI), add through configuration | Email+IPv6 immediately; current types only (onion/PSL misclassification) |
| 2 | Test corpus format | **Case table + golden** | Unit precision by bucket + end-to-end checking | Case table only; golden only |
| 3 | Test isolation | **Tests do not rely on project artifacts** | Changing a source/field/configuration must not break tests; test the logic | Tests against `dataframe/`/`source/`/production configuration |
| 4 | Classification oracle | **Curated rule-based corpus** | The hand-filled reference is incomplete (no variants 2/4) and sometimes inaccurate | Reference as the oracle |

<a id="нормализация-зафиксировано"></a>
## Normalization (established)

- Strip trailing punctuation (`;`, `,`, quotes).
- `:` means a port **only in the authority** (before the first `/`); otherwise it is part of the path.
- Extract the host from a URL for `host_match`.
- Onion address → variant 1 (special TLD, outside the normal PSL).

<a id="следствия"></a>
## Consequences

- `.onion`/telegram patterns + normalization rules are declarative (`ioc.*`), RE2-safe.
- The test corpus (case table + golden) lives in `src/test/resources`, is synthetic
  and isolated; test output goes to `@TempDir`.
- Use the PSL with its private section (Guava): duckdns/workers/ngrok/portmap →
  variant 1; providers outside the PSL (`tw1.ru`, …) → variant 2, recorded as an
  expectation in the corpus.
- Current unit tests are already isolated (inline data), satisfying the principle.

<a id="открытые-вопросы"></a>
## Open questions

- The precise list of “non-PSL providers” in the data will be refined while
  building the corpus (variant 2 expectations).
- Exact base32 boundaries for `.onion` (v3 = 56 characters), when implementing the pattern.
