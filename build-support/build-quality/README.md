# Shared analyzer verification tools

## Purpose and structure

JDK-only source/configuration/report checks shared by the three analyzer report
owners. This directory has no POM and is not a reactor module. The tools must
remain outside production dependencies and production coverage denominators.

| File | Responsibility |
|---|---|
| [BuildQualityVerifier.java](BuildQualityVerifier.java) | Reconcile reactor/manifest/dependency/source scope and control-specific late report contracts |
| [BuildQualityVerifierTest.java](BuildQualityVerifierTest.java) | Temporary synthetic reactors, valid configurations and negative mutations |
| [SpotBugsBaselineVerifier.java](SpotBugsBaselineVerifier.java) | Validate accepted identities, emit narrow filter, compare raw/filtered/aggregate findings and propose non-accepting deltas |
| [SpotBugsBaselineVerifierTest.java](SpotBugsBaselineVerifierTest.java) | Synthetic baseline, proposal constraints, analyzer health and aggregate-union scenarios |
| [SpotBugsReportFilter.java](SpotBugsReportFilter.java) | Apply SpotBugs' workflow filter to existing raw XML without another bytecode-analysis pass |

## Invocation and dependency ownership

The [root POM](../../pom.xml) compiles these default-package classes together with the
coverage/test-lifecycle tools using the configured Java release, then runs all
four standalone contract harnesses once in root `validate` (`inherited=false`).
The real manifests and baseline are validated before child builds. Report POMs
reuse these tools for their late checks; PMD also uses Java source-file execution.

Run the early contract gate from the repository root:

```bash
./mvnw -B -ntp -N validate
```

This does not generate current production analyzer reports. Full `make verify`
and the separate `make pmd-analysis` own those executions. Harness success
validates exercised verifier behavior, not correctness of every production class.
Temporary synthetic reactors do not alter the checkout.

The verifiers use JDK XML/filesystem APIs without a parser or Maven-model library.
`SpotBugsReportFilter` compiles with the JDK alone but reflectively needs the
compatible SpotBugs engine on its runtime classpath, supplied by AntRun's plugin
realm. It is a version-sensitive bridge, not a general public filtering API.

## Contracts and limits

`BuildQualityVerifier` accepts `spotbugs`, `cpd` or `pmd`, with `validate` or
`verify-reports`; PMD late verification also selects `policy` or `watchlist`.
Early validation checks accepted reactor coordinates/dispositions, report ordering,
source roots, cleanup/wiring, exact rulesets and relevant suppression/skip policy.
Late checks require usable XML/HTML and enforce the selected report contract.

CPD compares reported unique file paths to every current in-scope Java file.
PMD reports list finding-bearing files: the verifier checks those paths against
the admitted inventory, accepted rules and engine, and rejects processing/config
errors. It does not reinterpret an omitted PMD `<file>` as a missing analyzed file.
SpotBugs checks expected report pairs/exclusions; its baseline verifier additionally
compares the raw aggregate to the module findings as a multiset.

`SpotBugsBaselineVerifier` has `validate`, `verify` and `propose` modes. Validation
writes only the generated filter. Verification compares raw identities before
checking filtered visibility and aggregate integrity. Proposal writes only under
`target/` and cannot accept findings or edit the tracked baseline. See the
[SpotBugs owner](../spotbugs-report/README.md) for identity and review rules.

The filter bridge intentionally returns when raw XML is absent. Its successful
exit alone is not completeness evidence; late scope/baseline checks reject missing
required reports. The full contract is provided by the ordered lifecycle, not
by each helper invocation in isolation.

All command failures return nonzero with diagnostics. Synthetic negative cases
must assert both rejection and the expected diagnostic. Add a regression mutation
for a corrected fail-open path; keep new control-specific policy with its owner,
and common reactor/set/report mechanics here. The matrix covers reviewed cases,
not every possible hostile or malformed build input.

[CPD](../cpd-report/README.md), [PMD](../pmd-report/README.md) and
[build-quality internals](../../docs/dev/build-quality.md) own their report/configuration details.
