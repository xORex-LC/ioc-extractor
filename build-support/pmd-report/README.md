# PMD source policy and watchlist

## Purpose and boundary

Report-only Maven POM `ioc-pmd-report` owns the adopted production-source policy
and the separate diagnostic ownership/size watchlist. Dependencies cover the full
analyzed production set for ordering/type resolution. The positive source-root
list excludes TCK/tests, parent/report POMs and generated/vendor sources. It is
not a runtime dependency or published artifact.

## Structure

| File | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Profile selection, explicit sources, pinned PMD plugin realm, cleanup and late verification |
| [pmd-scope.tsv](pmd-scope.tsv) | Complete reactor dispositions |
| [pmd-ruleset.xml](pmd-ruleset.xml) | Exact 22-rule adopted policy and calibrated properties |
| [pmd-advisory-counts.tsv](pmd-advisory-counts.tsv) | Reviewed nonzero counts by exact rule reference; omitted adopted rules require zero findings |
| [pmd-watchlist-ruleset.xml](pmd-watchlist-ruleset.xml) | Exactly PreserveStackTrace, CloseResource and NcssCount |
| [Shared verifier](../build-quality/BuildQualityVerifier.java) | Scope, engine/ruleset, report-health and count contracts |

Policy evidence is `target/pmd/pmd.xml` and `pmd.html`. Watchlist evidence is
`target/pmd-watchlist/pmd.xml` and `pmd.html`. Keeping outputs separate prevents
a diagnostic invocation from replacing the adopted policy report.

## Commands and lifecycle

Run from the repository root:

```bash
make pmd-analysis
```

Ordinary `make verify` includes this POM in reactor topology but does not activate
source analysis. Root `validate` still protects its declared policy/scope. The
regular CI policy job calls [tools/ci/pmd.sh](../../tools/ci/pmd.sh) independently; external
branch/ruleset settings determine whether that status is required for merging.

The policy facade selects this report and its complete upstream graph with
`-pl build-support/pmd-report -am`, activates `pmd-analysis`, compiles inputs and
skips tests, JaCoCo and SpotBugs. It runs one `aggregate-pmd-no-fork` after the
declared production dependencies. Selecting this owner avoids competition with
the independent full-reactor aggregators. Plugin/PMD engine versions belong to
root/report configuration and the verifier's engine contract, not this README.

For an explicit deferred-rule review:

```bash
make pmd-watchlist
```

The watchlist facade activates both profiles and selects its own ruleset/output
mode. Use it for exception/resource ownership, material size growth, changed
PMD/JDK/framework assumptions or an explicit complete PMD review. Findings are
advisory/count-unconstrained, but analyzer/ruleset/report errors still fail.
It is not a routine merge gate and does not refresh PMD policy evidence.

## Enforcement and review

Root validation pins source/dependency scope, exact rule references/properties,
source suppression restrictions, cleanup and late verifier wiring. The PMD-specific
cleanup clears only its selected output directory; inherited initialization can
also remove module SpotBugs outputs. Run PMD before full verify or archive prior
reports when retaining both evidence sets. The late gate checks XML/HTML,
engine version, accepted finding rules/paths and processing/configuration errors.
PMD reports list finding-bearing files; that list is not a full source manifest.

For adopted policy, unlisted rules have zero tolerance and listed rules must
match their exact reviewed counts. This is not a budget for substituting different
findings. Reductions also require review so no unused allowance remains.
`CognitiveComplexity` uses `reportLevel=16`; `ExcessiveParameterList` uses
`minimum=13`. Threshold/rule/suppression changes are policy changes, not routine
ways to make a report pass.

Inspect the full report on count drift and the affected findings after code
changes. Fix the issue or retain an explicit reviewed rationale and update the
owning snapshot in the same change. Do not regenerate a baseline wholesale.
A successful policy facade records its own commit/worktree fingerprint;
`make context` exposes `pmd.fresh` separately from `verify.fresh`.

[Shared tools](../build-quality/README.md) describe synthetic contracts and
[build-quality internals](../../docs/dev/build-quality.md#pmd-source-analysis-policy-and-watchlist)
owns adoption, watchlist assumptions and triage workflow.
