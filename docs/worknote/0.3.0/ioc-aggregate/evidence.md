---
title: "DATA-AGGREGATE-01 — Evidence ledger"
version: "0.3.0"
status: "Discovery in progress"
document_type: "Evidence ledger"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — evidence ledger

## Analysis baseline — 2026-09-21

- Checkout: `r030-libraries`, HEAD `e3f88ccf69ccb5147b98169ff6c2ba9742fb0f7a`.
- Version: `0.3.0-SNAPSHOT`; tracked worktree clean before documentation edits.
- `make context`: passed; local verify and PMD evidence refer to `3307d1e2`,
  both freshness flags false. No current full-gate claim.
- Inspected repository instructions, root architecture/policy documents,
  release baseline/status, TTL/import worknotes, processing/storage/export docs,
  configuration assembly, mapping and canonical ordinary mutation code.

## Local sample inspection

Python standard-library CSV parsing of local `dataframe/IOC_aggregate.csv`
found 9,557 data rows, all five columns wide: 9,035 hash-bearing rows and 522
IP-bearing rows. Both match columns were NULL throughout. There were 219 names,
no exact duplicate rows and no repeated raw `(ip_address, hash)` pair.

Ignoring letter case, 65 hash values failed the expected hexadecimal
MD5/SHA1/SHA256 length/content check. One IP had a trailing dot. This was a
structural/data inspection, not an execution of the service's parser or an
assertion about its precise rejection outcomes.

The sample is gitignored local material; these counts are an analysis snapshot,
not portable qualification evidence or a substitute for consumer fixtures.

## Implementation and validation status

No production/configuration edits, feature tests, runtime smoke, full verify,
PMD run or external/deployment qualification performed for this feature.
Documentation initialization checks passed: `make docs` reported 987 total
links, 377 unique, 852 OK, 135 excluded and 0 errors. A separate bundle-local
relative-link check found no missing targets. `git diff --check` passed.
No production validation is inferred from these documentation checks.

## Discovery follow-up — 2026-09-21

`make context` confirms `feature/dataframe/new-artifact`, same HEAD, no upstream,
and transferred documentation changes. Inspected `MarkerSourceAttributor`,
classpath section-marker regexes, managed-import documentation and explicit
contract/routing declarations. No production changes or feature tests.

## Decision-recording pass — 2026-09-21

Rechecked branch/HEAD with `make context`; unchanged from the feature-branch
checkpoint. A standard-library CSV census classified all 219 distinct labels:
119 BIB, one request, 97 dated underscore FSTEK, one undated underscore FSTEK,
and one spaced FSTEK label. The production relevance is owner-provided evidence,
not an inspected stand. Recorded accepted requirements and separated remaining
technical proposals; production code/configuration remain unchanged.

## Concurrency clarification

Reviewed `IngestFlowConfiguration`, `FileSourceMessageHandler`,
`IocConfigPreflight`, `IngestionService` and `DataframeImportDrainCoordinator`.
Confirmed sequential normal document dispatch, separate delayed retry execution,
per-content-key exclusion and a separate durable global import lane. No runtime
concurrency test was executed. Updated the architecture assessment to correct
the earlier generic parallel-document example.

## Accepted behavior checkpoint

Recorded owner acceptance of last labeled occurrence within a document,
configurable empty-name preservation and durable registration priority unchanged
by errors/retries/restart. This is requirements evidence, not implementation or
runtime qualification. `make context` confirms the same feature branch and HEAD;
production code remains unchanged and full verify/PMD evidence remains stale.


## Technical-design review — 2026-09-22

`make context` reconfirmed feature/dataframe/new-artifact at
e3f88ccf69ccb5147b98169ff6c2ba9742fb0f7a. Existing verify and PMD remain stale.
Prepared technical design, dependency alternatives and P0–P7 implementation
slices; expanded planned verification to cover admission, mutation and recovery.

Read-only integration reviews examined prehash document claims, separate import
coordination, oneshot admission, canonical mutation outcomes, receipt reuse and
processed-import source bindings/fingerprints. Review corrections explicitly cover
missing-registration resume, terminal handshakes, unresolved crashed CLI records,
file replacement ambiguity and stage-policy drift. These are proposed remedies,
not executed defect fixes or runtime evidence.

Library alternatives are documented with primary-source links. No dependency,
production Java, configuration preset, database or published library was changed.
The design leaves owner questions explicit and does not claim production readiness.

Documentation checks for this design pass: `make docs` passed (1,007 links,
388 unique, 863 OK, 144 excluded, zero errors). Bundle-local checking included
all untracked Markdown files: 25 relative links, no missing targets or trailing
whitespace. `git diff --check` passed. These checks validate documentation only;
no production tests or full build were required or run for this documentation task.

## Network carrier amendment — 2026-09-22

Inspected live masks configuration, AddressIpValueProvider,
AddressUrlValueProvider, NetworkAddressClassifier, IndicatorFeatures and
RegexIndicatorExtractor. Recorded URL/domain inclusion, independent-carrier rows,
configurable structural gates/shape validation and future correlation boundaries.
Scheme-less/decorated-address routing remains a proposal awaiting clarification.
`make docs` passed: 1,017 links, 390 unique, 872 OK, 145 excluded, zero errors;
`git diff --check` passed. No production changes or feature tests were performed.


## Lifecycle restart decision

Owner accepted recreation after expiry from delayed previously uncommitted input.
Documented the distinction from committed-operation replay and from source threat
freshness. This is requirements evidence; no production behavior was changed.
