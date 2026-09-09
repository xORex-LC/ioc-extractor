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

Focused parser and bootstrap integration tests, dependency graph review,
`make verify`, `make pmd-analysis` and exact-commit security scan are required.
Local results and remote run links will be retained in the execution report.
No new suppressions or analyzer-baseline relaxations are authorized.

R030-SEC remains in progress. Repository governance, broader source security,
publication controls and other goal requirements are outside this dependency slice.
