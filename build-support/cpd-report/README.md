# Production-source duplication report

## Purpose and structure

Report-only Maven POM `ioc-cpd-report`: repository-wide PMD CPD over a positive
list of production `src/main/java` roots. Dependencies equal the declared analyzed
production set and establish reactor ordering. This POM produces no runtime code
or published library; other production/report projects must not depend on it.

| File | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Ordering dependencies, explicit source roots, analyzer settings, exact duplication-group snapshot and late gate |
| [cpd-scope.tsv](cpd-scope.tsv) | Explicit disposition for the complete reactor |
| [BuildQualityVerifier](../build-quality/BuildQualityVerifier.java) | Shared topology/source/report validation |

Generated evidence is `target/cpd/cpd.xml` and `target/cpd/cpd.html`. Scope and
group count belong to the tracked manifest/POM, not copied report output.

## Analysis and enforcement

Root validation requires complete reactor coverage and exact agreement among
analyzed dispositions, ordering dependencies and configured source roots.
TCK/tests, build-only projects, generated outputs and non-reactor vendor trees
are outside the production-source universe. Explicit generated/vendor exclusions
provide a second guard; they are not a general exemption mechanism.

The report POM removes old `target/cpd` output in `initialize`. Its `verify`
execution runs `aggregate-cpd`, then requires nonempty valid XML/HTML and
reconciles unique reported paths with every current in-scope Java source.
`minimumTokens=75` is the accepted detection threshold. Annotation, identifier
and literal differences are not ignored by this configuration.

`ioc.cpd.expectedDuplications` is an exact reviewed count of duplication groups,
not duplicate lines, a per-file allowance or an automatic refactoring rule.
Growth and reduction both stop the gate until reviewed. Every finding remains
visible. A same-count replacement still requires semantic review: duplication
may represent deliberate independent contracts or harmful copied behavior.
A healthy analyzer and valid report are required even when the expected count
is unchanged.

## Verification and maintenance

Run from the repository root:

```bash
./mvnw -B -ntp -N validate
make verify
```

The first command checks configuration/synthetic contracts; the second creates
current production analysis and validates the full report. Use `make context`
for full-verification freshness. Do not interpret an older HTML file as evidence
for the current worktree.

When the count changes, review the full XML/HTML before refactoring or updating
the snapshot, with rationale in the same change. Do not hide occurrences through
weaker token settings, ignores, source exclusions or suppressions. Adding a Maven
project requires a scope disposition and, when analyzed, matching dependencies/
source roots. [Build-quality internals](../../docs/dev/build-quality.md#pmd-cpd) owns triage policy;
[shared harnesses](../build-quality/README.md) protect negative cases.
