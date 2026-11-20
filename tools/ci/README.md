# CI tools

## Purpose and entry points

These noninteractive leaves are shared by the [Makefile](../../Makefile) and
[workflows](../../.github/workflows). Workflows invoke leaves directly; local Make
is the developer facade. Each script locates the checkout from its own file.
Environment setup is explicit and separate from gate execution.

| Source | Contract |
|---|---|
| [build.sh](build.sh) | Full Maven `verify` and atomic local verification receipt |
| [pmd.sh](pmd.sh) `policy\|watchlist` | Adopted PMD policy or separate deferred-rule review |
| [packaging.sh](packaging.sh) | ShellCheck across packaging/tools, then packaging and tools shell contracts |
| [docs.sh](docs.sh) | Offline lychee links for published docs, tools, release records and listed root/module READMEs |
| [codecov.sh](codecov.sh) `verify-input\|require-report` | Project-owned coverage revalidation or presence check of downloaded aggregate XML |
| [test-pilots.sh](test-pilots.sh) `mutation\|stability` | Opt-in PIT domain pilot or seeded cohort repetitions |
| [dependency-security.sh](dependency-security.sh) `update\|scan\|report` | Explicit NVD refresh, scan without data updates, or retained report paths |
| [library-publication.py](library-publication.py) | Component admission, bundle validation, signing, distribution and consumer execution |
| [library-publication-groups.txt](library-publication-groups.txt) | Reviewed public group allowlist for standalone consumer repository routes |

Requirements follow the leaf: Java 21+/Maven wrapper for builds; ShellCheck and
shell-contract prerequisites for packaging; lychee for links; Python 3/GnuPG for
publication contracts. The [root reference](../README.md) explains bootstrap and
platform support. Analyzer internals and registries belong to
[build-support](../../build-support/README.md).

## Local parity and receipt semantics

`make ci-build`, `make ci-pmd`, `make ci-packaging` and `make ci-docs` select the
matching leaf. `make ci`/`make pre-push` run PMD → verify → packaging → docs
sequentially. Separate CI library-consumer jobs, reporting jobs, security refresh
and pilots are not part of that chain.

`build.sh` executes `./mvnw -B -ntp -T 1C verify`. `pmd.sh policy` selects
`build-support/pmd-report` with upstreams and skipped tests/JaCoCo/SpotBugs;
this avoids competing aggregate mojos. Policy and watchlist output live under
that report module's `target/`; watchlist does not update the policy receipt.

Build and PMD policy atomically replace `.dev/state/last-verify.env` and
`last-pmd.env` (or a validated `DEV_STATE_ROOT`). Records contain result, commit,
worktree fingerprint and UTC completion time. If the tree changes during the run,
the result becomes `invalidated`; these two leaves still return Maven's exit
status, so exit zero alone is insufficient. Check result, commit and freshness
with `make context` before claiming current evidence. Failed or invalidated runs
replace the previous receipt. Ignored files are outside the fingerprint; a local
receipt does not replace the release's exact-source/binary/CI evidence.

## Coverage reporting and diagnostic pilots

`codecov.sh verify-input` requires the verifier compiled by the build and reruns
the same fail-closed `CoverageVerifier verify-reports` against scope, ratchets,
floors and aggregate/local reports. `require-report` only checks that the
artifact-downloaded aggregate XML exists and is nonempty. Neither uploads data.
The separate best-effort reporting job grants OIDC permissions only to the
uploader, which does not execute the reactor. Codecov status cannot replace
project-owned [coverage integrity and floors](../../docs/TESTING.md).

`test-pilots.sh mutation` runs only `core/ioc-domain`, retaining stable HTML/XML
under its `target/pit-reports/` and a summary under `target/test-pilots/`.
Zero mutation thresholds keep survivors diagnostic; tool/test failures still fail.
Broader adoption remains [TOOL-5](../../docs/KNOWN-ISSUES.md#tool-5).

`test-pilots.sh stability --seed 42 --repeat 3` runs functional modules sequentially
with Surefire/Failsafe and JUnit random ordering, incrementing the seed per run.
Each successful cohort passes the exact source/report-union verifier; XML and seed
are archived before the next lifecycle cleanup. The first failure stops execution
without retry and retains partial evidence under `target/test-pilots/stability/`.
A new invocation replaces that diagnostic output directory; preserve needed evidence.

## Security data boundary

Only `dependency-security.sh update` requires `NVD_API_KEY`; Maven receives its
environment-variable name. `scan` uses `autoUpdate=false`, HTML/JSON output and
a CVSS 7 failure threshold, with Assembly and OSS Index analyzers disabled.
`DEPENDENCY_CHECK_DATA` selects an isolated cache; a missing explicit cache fails
with an update instruction. Without an override, Maven's default data directory
applies. The weekly/manual workflow caches `.dependency-check-data/` and runs
update and scan as separate steps. `report` only prints existing root report paths;
it neither scans nor establishes their freshness.

## Library publication tooling

The descriptor table `COMPONENTS` in [library-publication.py](library-publication.py)
is the executable authority for module, package markers, exact published POM
closure, fixtures and admission. `module`/`artifact` print selectors; `prepare`
copies already built main/POM/sources/Javadoc files into a new bundle and records
hashes/source identity. `check` validates the retained bundle; `--signed` additionally
requires signed metadata and a clean recorded source. The Make `library-bundle`
target builds before `prepare`; it refuses to overwrite an existing bundle.

`consume` uses the [standalone fixtures](../fixtures/README.md) with temporary
settings/projects/caches, actual guide examples and per-file hash/origin checks.
All own artifacts stay on the selected local/Central/GitHub route; public groups
and plugins use separate reviewed Central routes. Resolver rules cover original
and mirrored repository IDs, including the same-URL Central case. Own groups must
never enter the public allowlist. Revisit filters, graph expectations and origin
checks together when updating Maven/Resolver or dependencies.

The admitted selectors are `concurrency`, `diagnostics`, `logging`, `configuration`,
`events` and `events-spring`. Logging runs two consumers; Spring events requires
`--dependency-bundle` for the exact same-version events core. Local assembly uses
one temporary repository containing both bundles, not the developer Maven cache.
JDK-only components remain dependency-free; required framework APIs, scopes and
versions are checked exactly. Fixture-only BOM/negative profiles qualify consumer
behavior, not additional supported library baselines.

`sign`, `central` and `github` are effectful release operations. `check-core-release`
checks core tag/run provenance and repository bytes before adapter admission;
`record-qualification` binds successful two-repository consumer logs to the
original bundle. Adapter release/recovery retains the qualified signed core and
its receipt. Recovery preserves signed bytes and deployment identity, skipping
rebuild/re-signing. `central` may create/upload a deployment even without
`--publish`; that flag controls automatic publication, not a dry run.

Use the [publication procedure](../../docs/guides/library-publication.md) for
credentials, protected workflow admission, network actions and recovery. Accepted
independent version ownership is distinct from the still-shared Maven revision
([TOOL-9](../../docs/KNOWN-ISSUES.md#tool-9)). Historical component evidence belongs
to release records; a local test does not renew remote qualification or close
[configuration CI recovery](../../docs/KNOWN-ISSUES.md#tool-8).
