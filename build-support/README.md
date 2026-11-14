# build-support

## Purpose and boundary

Repository-owned build verification: analyzer scope and report integrity, test
lifecycle completeness, coverage policy and reviewed finding/count ratchets.
This tree contains four report-only Maven POM projects and two JDK-tool directories
outside the reactor. It is not part of the application's runtime or publication
surface.

Report projects depend on the declared production set to establish ordering and,
where needed, analysis classpaths. Production and test-support modules must never
depend on these report projects or verifier classes. Parent/plugin configuration
and CI scripts remain outside this directory under their existing owners.

## Structure

| Directory | Responsibility |
|---|---|
| [build-quality](build-quality/README.md) | Shared analyzer registry/report checks, exact SpotBugs baseline, filter bridge and synthetic harnesses; not a Maven project |
| [test-quality](test-quality/README.md) | Surefire/Failsafe source taxonomy, count ratchet and late report union; not a Maven project |
| [coverage-report](coverage-report/README.md) | JaCoCo aggregate, scope/floors/ratchets and late test/coverage gates |
| [spotbugs-report](spotbugs-report/README.md) | Raw/filtered aggregates and exact reviewed-finding enforcement |
| [cpd-report](cpd-report/README.md) | Repository-wide production-source duplication report and group-count ratchet |
| [pmd-report](pmd-report/README.md) | Separately activated adopted source policy and optional ownership/size watchlist |

## Lifecycle and evidence

| Entry point, run from repository root | What it proves |
|---|---|
| `./mvnw -B -ntp -N validate` | Compile JDK tools, run four synthetic harnesses, validate current source/configuration universes and generate the SpotBugs presentation filter; no real analyzer/test-report run |
| `make verify` | Full test/build lifecycle, JaCoCo coverage/test-report gates, raw/filtered SpotBugs and CPD gates |
| `make pmd-analysis` | Adopted PMD source policy, exact per-rule counts and report integrity; separate from `make verify` |
| `make pmd-watchlist` | Explicit diagnostic findings; analyzer/configuration/report failure still fails the command |
| `make context` | Stored result/commit/worktree freshness for full verify and PMD policy independently |

Use the repository Maven wrapper with the validated JDK 21 toolchain.
Root `validate` compiles nine JDK-only Java sources/harnesses from three local
source directories into `target/build-quality-verifier`. The harnesses are
standalone Java programs, not JUnit/Surefire suites or production coverage inputs.
Root/child `initialize` cleanup and late report checks prevent older outputs from
substituting for the selected build. Cleanup deliberately preserves test/coverage
outputs during `-DskipTests` analyzer invocations; that does not make those outputs
fresh full-verification evidence.

Inherited SpotBugs initialization still removes module reports during a skipped
PMD analysis. To retain both analyzer report sets locally, run PMD policy before
full verify, or archive the earlier evidence. Freshness receipts identify source/
worktree results; they do not guarantee that every report file is still present.

Full verify and PMD policy use separate workspace fingerprints. Freeze edits
before either command; check both freshness flags before claiming both controls
are current. Calling Maven directly does not record the Make/CI facade's freshness
receipt. The watchlist does not refresh regular PMD-policy evidence.

## Policy and maintenance

Scope manifests reconcile the complete reactor, including explicit excluded
projects. The test-support TCK and report POMs remain outside production analyzer/
coverage denominators. A new project needs a reviewed disposition, applicable
report dependencies/source roots and test/coverage policy updates in the same
change. Generated `target/` evidence is not a tracked policy source.

SpotBugs accepts exact reviewed identities. CPD and adopted PMD use exact counts:
both increases and reductions require review; an unchanged count can still hide
a semantic replacement. Coverage combines exact-ratio ratchets with fixed floors.
Never lower a floor, broaden a suppression or silently refresh a snapshot to make
a build green. Inspect the owning report and document the actual disposition.

[Build-quality internals](../docs/dev/build-quality.md) owns the cross-module mechanism and
review workflow; [TESTING.md](../docs/TESTING.md) owns useful test design and coverage policy.
[tools](../tools/README.md) owns Make/CI scripts, PIT/stability pilots and dependency/security
commands. Dependency hygiene is opt-in advisory; Dependency-Check is a separate
security workflow. Neither is implied by ordinary `make verify`. Analyzer/plugin
versions are owned by the [root POM](../pom.xml), with PMD engine alignment also checked
by the shared verifier.

[CI](../.github/workflows/ci.yml) retains report artifacts, including late-failure evidence.
Codecov reporting is advisory. Whether a CI status is required to merge belongs
to external GitHub branch/ruleset settings, not these POMs.
