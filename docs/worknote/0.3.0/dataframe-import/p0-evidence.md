---
title: "DATA-IMPORT-01 P0 evidence"
version: "0.3.0"
status: "Committed after working-tree verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P0 evidence

## 1. Evidence boundary

This evidence covers only the P0 contract and boundary baseline approved on
2026-08-23. It was produced on branch `feature/dataframe-import` from base
commit `5fc311f9ed4f3e320a23c2b5e1bac6ca2f0a0b99` with the P0 changes still
uncommitted. Those exact code and configuration changes were subsequently
split into logical commits. The evidence proves the pre-commit working tree,
not a committed-HEAD freshness gate.

P0 does not admit, parse, stage or promote operator CSV files. Runtime intake,
CSV and filesystem adapters, durable import schemas, local/SMB ownership,
promotion and recovery remain P1+ work. The production default remains
`ioc.dataframe-import.enabled=false`.

## 2. Delivered contract surface

- ADR-0024 publishes the managed-import decision without reopening legacy CSV
  lookup or seed behavior.
- `ioc-application` owns the framework-free draft/compiler/catalog,
  fingerprints, tri-state cell and policy values, use-case ports and driven
  ports.
- `ioc-application-tck` provides abstract ledger and atomic-writer contract
  skeletons for later adapters.
- bootstrap owns the Spring configuration shape, enum converters, mapping into
  the application draft and collect-all preflight compilation.
- artifact identity configuration declares versioned record keys and match-key
  names independently.
- Enforcer and ArchUnit prohibit Spring, Commons CSV, JDBC, SMBJ and outward
  implementation dependencies from the import application boundary.
- English/Russian operator references, configuration metadata, module maps and
  boundary documentation describe the disabled P0 surface.

## 3. Executable evidence

| Command/check | Result |
|---|---|
| focused catalog compiler test | `7` tests, `0` failures/errors; includes deterministic fingerprints, collect-all validation, null-key aliases, authority ceiling and null-tolerant immutable snapshots |
| strict configuration tests inside the full reactor | disabled default, valid enabled compilation, unknown nested-key rejection and semantic collect-all cases passed |
| ArchUnit and Enforcer inside the full reactor | application import boundary and banned transitive integration dependencies passed |
| `make verify` | all `25` reactor projects `SUCCESS`; `970` tests, `0` failures, `0` errors, `2` existing real-SMB CHANGE_NOTIFY skips |
| aggregate SpotBugs gate | accepted baseline unchanged at `99` raw findings and `0` visible findings; no P0 suppressions or baseline acceptances added |
| aggregate PMD/CPD and synthetic golden checks | passed as part of `make verify` |
| verification-matrix decision scan | `41` unique IDs, covering I-01 through I-41 |
| `make docs` and `git diff --check` | documentation convention/link checks passed; no whitespace errors |

The focused catalog command was:

```text
./mvnw -B -ntp -pl core/ioc-application -am \
  -Dtest=DataframeImportCatalogCompilerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The final full-reactor gate completed at `2026-08-23T22:01:01+08:00` in
`02:43` wall-clock time.

## 4. Failure review

The first full analysis exposed 30 new `EI_EXPOSE_REP` reports because
SpotBugs did not infer the existing null-preserving defensive snapshot helper.
The implementation was changed to expose explicit read-only accessors, and
focused tests now prove caller isolation, immutability and preservation of
invalid null elements for collect-all validation. The final raw report contains
no new identities, so the accepted baseline was not widened.

The final Java/null-safety audit also found that a null alias-map key could
reach natural-key sorting before collect-all validation. Sorting is now
null-tolerant and a focused regression proves both invalid entries are reported
without aborting catalog compilation.

One intermediate focused test used the ambiguous `Arrays.asList(null)` varargs
form and failed before exercising production code. The fixture now supplies an
explicitly typed null; the focused test and the subsequent full reactor pass.

## 5. Remaining gate

Committed-HEAD freshness remains deferred: rerun `make verify` after a complete
implementation slice and confirm the recorded commit/tree with `make context`.
The approved next scope is P1 followed by P2.
