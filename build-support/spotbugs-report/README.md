# build-support/spotbugs-report

## Purpose

Non-production Maven report module that creates the authoritative reactor-wide
SpotBugs XML/HTML report and validates report completeness after all production
dependencies have completed.

**Layer rule:** dependencies exist only to define the analyzed production
universe and reactor ordering. This module contains no runtime code and is not a
published library.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | Production dependency ordering, raw/filtered `spotbugs-aggregate` executions and the late blocking gate |
| `spotbugs-scope.tsv` | Single disposition registry for every root and child reactor project |
| `spotbugs-accepted-findings.xml` | Reviewed exact-finding baseline, concrete review-trigger catalog and the only suppression source of truth |
| `../build-quality/BuildQualityVerifier.java` | Shared JDK-only scope and report-integrity verifier |
| `../build-quality/SpotBugsBaselineVerifier.java` | Exact raw-baseline, analyzer-health and aggregate-union verifier |
| `../build-quality/SpotBugsReportFilter.java` | Version-pinned bridge from raw XML to filtered XML without another analysis pass |
| `target/spotbugs-raw/spotbugs-raw.xml` | Unfiltered machine-readable reactor aggregate; blocking evidence |
| `target/spotbugs-raw/spotbugs.html` | Unfiltered human-readable reactor aggregate for triage |
| `target/spotbugs/spotbugs.xml` | Filtered machine-readable reactor aggregate |
| `target/spotbugs/spotbugs.html` | Filtered human-readable reactor aggregate |
| `../../target/build-quality/spotbugs-baseline-proposal.xml` | Untracked, non-accepting new/stale identity delta produced on demand |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production, test-support or coverage-report module.

## Notes

`core/ioc-application-tck`, the root parent, `coverage-report` and `cpd-report`
are explicitly excluded from production-bytecode analysis. `spotbugs-report`
has the separate `aggregate` disposition. The root lifecycle removes stale
module-local SpotBugs outputs during `initialize`, so an incremental build cannot
satisfy the integrity gate with a report from a prior invocation.

The root-only `validate` gate runs the shared verifiers before child projects
are built. It requires the registry to cover exactly the root Maven reactor,
checks each path against its POM coordinates and packaging, requires explicit
`skip=true` for every excluded child project, and requires the report module's
dependencies to equal the `analyzed` artifact set. Adding any reactor project
therefore fails closed until it receives an explicit disposition and, when
analyzed, an ordering dependency. A synthetic-reactor contract harness in
`../build-quality` protects these rules against accidental weakening. The final
`verify` step derives all expected XML/HTML paths from the same registry,
validates their structure, rejects reports from excluded scopes and reconciles
the raw aggregate with the exact multiset union of module raw findings.

SpotBugs runs once per analyzed module without exclusions and writes
`target/spotbugs-raw/spotbugs-raw.xml`. The raw document is the enforcement input.
The generated narrow filter is applied afterwards by SpotBugs' workflow filter,
producing `spotbugs.xml`; `default.xsl` renders its module HTML. This separation
means a broad-enough presentation selector cannot hide a new finding from the
blocking comparison.

The accepted baseline contains 74 reviewed findings represented by 68 narrow
selectors: 56 analyzer false positives and 18 exception-policy signals whose
generic advice is inapplicable to a documented boundary contract. Each entry
stores its evidence ID, module, bug metadata, hash and occurrence, primary
class/member/JVM descriptor, source/bytecode anchor, disposition, owner,
rationale, a reference to a concrete review trigger and presentation selector.
The root-level trigger catalog names the external invariant or boundary change
that invalidates the disposition; generic conditions such as “code or analyzer
change” are rejected, as are unknown, duplicate and unused triggers. Hashes are lowercase
hexadecimal values of 1–32 characters because SpotBugs does not preserve leading
zeroes.

Exact comparison uses module + type + hash + occurrence + priority/rank/category
+ primary class/member/signature + source path + bytecode offset. Source line is
retained for diagnostics but is advisory, so unrelated line shifts do not churn
the baseline. Two findings without a bytecode location use the otherwise exact
class/member identity. A third occurrence with an already accepted hash still
fails because it has no baseline entry.

The operational `FindBugsFilter` is generated under
`target/build-quality/spotbugs-accepted-filter.xml`; it is never edited or
committed. Validation rejects package/category/pattern-wide selectors and every
accepted entry must supply an exact method and/or field. Removing a finding
without removing its entry fails as a stale acceptance. Analyzer errors,
missing classes, metadata drift, new or moved findings, visible filtered output,
missing reports and aggregate divergence all fail ordinary Maven `verify`.

The Maven aggregate goal uses `spotbugsXmlOutputFilename` both to discover
module XML and to choose its own XML destination. Raw module and aggregate XML
therefore share the independent `target/spotbugs-raw/spotbugs-raw.xml` layout;
filtered XML/HTML exclusively use `target/spotbugs/`. Root `validate` pins the
module execution and both aggregate executions to this topology, including
negative mutations for cross-directory output drift. Neither aggregate depends
on the other execution to create its destination directory.

When a new finding appears, fix it with a focused regression by default. Accept
it only through a reviewed `spotbugs-accepted-findings.xml` entry with explicit
evidence and a concrete review trigger. After a failed full analysis,
`make spotbugs-baseline-proposal` may render the raw new/stale identity delta to
`target/build-quality/spotbugs-baseline-proposal.xml`. The proposal cannot be
used as a baseline: it is constrained to `target/`, never edits the tracked
file, and deliberately omits disposition, owner, evidence, rationale, review
trigger and suppression. Missing or unhealthy module raw reports fail the
command. Toolchain, compiler or SpotBugs upgrades are
separate rebaseline events: inspect every identity/rank delta and never refresh
the file wholesale. A new Maven module first receives an explicit scope
disposition; an analyzed module must then produce both raw and filtered reports.

Module HTML is rendered from filtered native XML using the SpotBugs engine's
`default.xsl` resource. The raw aggregate receives a separate Doxia HTML view.
Every `spotbugs.version` update must verify the workflow filter entry point,
module XML/HTML, both aggregates and the complete negative fixture matrix;
replace the pinned bridge if a future plugin provides the same one-pass
raw/filtered contract directly.
