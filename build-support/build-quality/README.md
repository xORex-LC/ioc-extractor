# build-support/build-quality

## Purpose

Repository-owned, JDK-only verification of build-quality analyzer scope and
report integrity. The verifier is neutral infrastructure shared by SpotBugs and
PMD CPD; it contains no production code and is not a Maven reactor project.

## Structure

| File | Purpose |
|---|---|
| `BuildQualityVerifier.java` | Fail-closed reactor/manifest reconciliation and control-specific report validation |
| `BuildQualityVerifierTest.java` | Synthetic-reactor contract harness for happy paths and negative mutations |

The root POM compiles both files with AntRun and runs the contract harness once
in `validate` with `inherited=false`. It then validates active analyzer
manifests before Maven starts child projects. Report modules can run the same
verifier later in `verify`, after their reports exist.

## Contract tests

Run the root-only fail-fast gate with:

```bash
./mvnw -B -ntp -N validate
```

The fixture harness never changes the checkout. It creates temporary synthetic
reactors and verifies four happy paths plus these negative cases:

1. new reactor module without a disposition;
2. stale manifest module;
3. manifest/POM artifact mismatch;
4. duplicate manifest path;
5. analyzed non-JAR module;
6. analyzed module explicitly skipping SpotBugs;
7. SpotBugs-excluded child without explicit `skip=true`;
8. analyzed scope/report dependency drift;
9. report module without `aggregate` disposition;
10. missing SpotBugs module report;
11. unexpected SpotBugs report from an excluded module;
12. missing CPD production source root;
13. CPD configured-source drift;
14. accidental CPD test scope;
15. malformed CPD XML.

Each negative case must return a non-zero status and a stable, specific
diagnostic. Add a regression scenario whenever verifier behavior is tightened
or a fail-open path is found.

## Ownership

Control-specific manifests and report configuration remain with
`build-support/spotbugs-report` and `build-support/cpd-report`. Common reactor
parsing, dispositions and set reconciliation belong here; do not copy them back
into individual report modules.
