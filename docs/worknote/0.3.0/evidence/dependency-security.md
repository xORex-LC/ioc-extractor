# Dependency security remediation

## Scope

Forward-port the dependency repair from released 0.2.1 (source commit
`025b84fe567e90d4a4884f0667700a7b5c2b5fcc`) to `release-0.3.0`.
The cherry-pick is `98b3042c`; retain product version `0.3.0-SNAPSHOT`,
RE2/J 1.8, UTF-8 build settings, and the existing Tika 3.3.2 / Commons IO 2.22.0.

Updated Boot 4.0.8 manages Framework 7.0.9 and Integration 7.0.6.
Explicit Tomcat 11.0.25 and jsoup 1.23.1 management fixes versions still
selected by upstream BOMs. Bouncy Castle 1.85 aligns with Tika 3.3.2.
No application code, configuration, schema or public artifact contract changes.

## Prior maintenance evidence

[0.2.1](https://github.com/xORex-LC/ioc-extractor/releases/tag/v0.2.1)
was published from `6138464d8887b17da2574f9f4e5d8c9465e21d50`.
Its [exact-commit scan](https://github.com/xORex-LC/ioc-extractor/actions/runs/34349325936)
contains 128 dependencies and zero unsuppressed findings. Two reviewed JBIG2
false-positive suppressions remained in use; the unused-rule gate passed.
Default-branch Dependabot alerts #105–109 were confirmed fixed on 2026-09-09.
This evidence does not substitute for a scan of the 0.3.0 reactor.

## Qualification

The qualified commit is `3307d1e2846890773bfca35f14c2097e8df20077`.

- focused parser and bootstrap integration tests passed;
- the effective dependency graph and analyzer output were reviewed;
- `make verify` passed fresh on the qualified commit;
- `make pmd-analysis` passed with zero blocking findings and the accepted
  `21/21` advisory counts;
- [CI run 34374281499](https://github.com/xORex-LC/ioc-extractor/actions/runs/34374281499)
  passed on the same commit;
- [Dependency Security run 34374294199](https://github.com/xORex-LC/ioc-extractor/actions/runs/34374294199)
  passed on the same commit with 129 dependencies, zero unsuppressed findings
  and the existing two reviewed JBIG2 suppressions; report artifact
  `dependency-check-report-16` has ID `10113370027`;
- no new suppression or analyzer-baseline relaxation was introduced.

Local command evidence is retained under `.dev/security-0.3.0/`. It is ignored
scratch evidence; the commit and linked CI runs above are the reviewable record.
This qualification applies to the exact commit. A later 0.3.0 release candidate
requires a new exact-commit scan after its final changes.

R030-SEC remains in progress. Repository governance, broader source security,
publication controls and other goal requirements are outside this dependency slice.
