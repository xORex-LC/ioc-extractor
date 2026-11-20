> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0000-foundations.md) (relative link addresses rebased on relocation).

<a id="0000--фундаментальные-решения"></a>
# 0000 — Foundational decisions

- **Status:** accepted (recorded retrospectively)
- **Date:** 2026-06-21 *(decisions made at project inception)*
- **Related:** [../architecture.md](../ARCHITECTURE.md), [../principles.md](../PRINCIPLES.md)

<a id="контекст"></a>
## Context

Initial decisions on style, stack and the first artifact set, made before the
development journal existed. Recorded here to complete the history.

<a id="решения"></a>
## Decisions

| # | Question | Choice | Rationale | Rejected |
|---|---|---|---|---|
| 1 | Architectural style | **Clean Hexagonal (ports and adapters) + Onion** | Domain isolation, replaceable infrastructure, testability | Layered monolith; framework-centric |
| 2 | Language/toolchain | **Java 21**, **Maven** build | Industry standard, records/sealed/switch-patterns; Maven is ubiquitous | Gradle (unnecessary at inception) |
| 3 | Runtime/DI | **Spring Boot** (CLI, no web) | Rich configuration (`@ConfigurationProperties`), DI, fat JAR; keep the domain Spring-free | Micronaut; Guice; manual wiring |
| 4 | Regex engine | **RE2/J by default + JDK fallback** behind the `PatternEngine` port | Linear time/ReDoS-safe on messy input; JDK as a fallback | JDK only (backtracking risk) |
| 5 | Source reading | **Apache Tika** behind the `SourceReader` port | Format-agnostic (.htm/.docx/.pdf/…), auto-detection | jsoup (HTML only) |
| 6 | Output artifacts | **Separate CSVs** by purpose: initially network masks + file hashes, then `ip_list` and `address_blacklist` | Different schemas/IDs/normalization; routing by `IndicatorType` and artifact filters | One combined artifact |
| 7 | CSV | **Apache Commons CSV**; `;`+quotes+`NULL` dialect | Standard; `QuoteMode.ALL_NON_NULL`+`nullString` yields exactly the required format | Handwritten parser; univocity |
| 8 | CLI | **picocli** (+ spring-boot-starter) | Declarative commands, Spring `IFactory` integration | Custom argument parser |
| 9 | STIX/OpenIOC as the model | **No** (a lightweight domain model of our own; type vocabulary aligned with STIX) | Our artifacts are CSV; STIX is a future export adapter | STIX 2.1 as the internal model |

<a id="следствия"></a>
## Consequences

- Frameworks (Spring/Tika/RE2J/commons-csv/picocli/Guava) stay in `adapter`/
  `bootstrap`; `domain`/`application` remain free of them ([boundaries.md](../BOUNDARIES.md)).
- See development documents 0001–0005 for references and the history of subsequent decisions.

<a id="открытые-вопросы"></a>
## Open questions

None; subsequent refinements are recorded in the relevant development documents.
