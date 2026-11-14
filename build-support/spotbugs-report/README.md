# SpotBugs aggregate and exact-finding gate

## Purpose and boundary

Report-only Maven POM `ioc-spotbugs-report`: raw/filtered reactor aggregates and
exact reviewed-finding enforcement after the analyzed production modules finish.
Its dependencies equal the analyzed production set for ordering/aggregation.
TCK, root and other report POMs are excluded; this POM has the `aggregate`
disposition. No production/test-support project depends on this report owner.

## Structure and outputs

| File | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Raw/filtered aggregation and late blocking verification |
| [spotbugs-scope.tsv](spotbugs-scope.tsv) | Complete reactor dispositions, coordinates and rationale |
| [spotbugs-accepted-findings.xml](spotbugs-accepted-findings.xml) | Exact reviewed identities, concrete review triggers and only suppression source of truth |
| [BuildQualityVerifier](../build-quality/BuildQualityVerifier.java) | Module/aggregate scope, output topology and XML/HTML contracts |
| [SpotBugsBaselineVerifier](../build-quality/SpotBugsBaselineVerifier.java) | Baseline/filter generation, exact raw comparison, analyzer health and aggregate-union verification |
| [SpotBugsReportFilter](../build-quality/SpotBugsReportFilter.java) | Version-sensitive workflow-filter bridge; no second bytecode analysis |

| Generated path relative to this report module | Evidence |
|---|---|
| `target/spotbugs-raw/spotbugs-raw.xml` | Unfiltered aggregate used for blocking comparison |
| `target/spotbugs-raw/spotbugs.html` | Unfiltered aggregate for human triage |
| `target/spotbugs/spotbugs.xml`, `spotbugs.html` | Filtered presentation aggregate |

Analyzed modules use the same raw/filtered subdirectory layout under their own
`target/`. The generated filter lives at root
`target/build-quality/spotbugs-accepted-filter.xml`. Never edit/commit generated
reports or that filter as policy inputs.

## One analysis, two views

Root `validate` runs the synthetic harnesses, reconciles reactor/manifest/report
dependencies and excluded skip settings, validates the accepted baseline and
emits its narrow presentation filter. Initialization removes stale SpotBugs
outputs. Each analyzed module then performs one bytecode-analysis pass without
exclusions. The bridge filters that raw XML afterward; module HTML uses the
pinned engine's `default.xsl`.

The two aggregate executions collect raw and filtered XML separately. The plugin
uses `spotbugsXmlOutputFilename` for both discovery and destination, so raw XML
shares the independent `spotbugs-raw/spotbugs-raw.xml` layout at module and
aggregate levels. Do not merge output directories or depend on one aggregate
execution to create another's destination.

The late gate requires healthy raw module reports, exact baseline identities,
no visible filtered findings, expected report pairs and the raw aggregate's
exact multiset union. Analyzer errors/missing classes, new/stale/moved findings,
metadata drift, absent output or aggregate divergence fail. An empty filtered
report alone is insufficient evidence: raw comparison remains authoritative.

## Accepted identity and review

Each baseline entry records module, bug type/hash/occurrence, priority/rank/
category, primary class/member/JVM descriptor, source/bytecode anchor, disposition,
owner, evidence, rationale, concrete review-trigger reference and a narrow
presentation selector. Source lines are advisory; exact matching retains source
path and bytecode offset where available. Hashes allow 1–32 lowercase hexadecimal
characters because SpotBugs omits leading zeros. Another occurrence sharing an
accepted hash still needs its own accepted identity.

Selectors cannot suppress a whole package/category/pattern and must name an
exact method and/or field. False-positive and documented exception-policy
findings retain distinct dispositions. Trigger definitions name the external
invariant/boundary change requiring review; generic “code/analyzer changed” text,
unknown/duplicate/unused triggers and stale finding acceptances are rejected.
Filtering therefore cannot silently turn a broad selector into acceptance of a
new raw finding.

## Verification and handling failures

Run from the repository root:

```bash
./mvnw -B -ntp -N validate
make verify
```

The first command validates source/configuration contracts; only the complete
analysis supplies current report evidence. After a late failure with retained,
healthy raw reports, this command creates a proposal without accepting anything:

```bash
make spotbugs-baseline-proposal
```

Its output is root `target/build-quality/spotbugs-baseline-proposal.xml`. It is
restricted to `target/`, never edits the tracked baseline and omits acceptance
metadata: owner/disposition/evidence/rationale/review trigger/suppression. Missing
or unhealthy raw reports fail the proposal command. Preserve failed-run reports;
do not clean them away before triage.

Fix new defects with focused regression evidence. Any acceptance or removed stale
entry needs explicit semantic review in the tracked baseline. Engine/compiler/
toolchain upgrades require inspecting identity/rank deltas and testing the filter
bridge plus raw/filtered XML/HTML topology; never rebaseline wholesale.
[Build-quality internals](../../docs/dev/build-quality.md#spotbugs-exact-finding-ratchet) owns the
full workflow; [shared tools](../build-quality/README.md) owns synthetic mutations.
