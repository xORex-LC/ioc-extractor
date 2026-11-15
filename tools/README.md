# Repository tools

## Purpose and boundary

This directory implements the local [Makefile](../Makefile) facade and the
explicit leaf commands used by [GitHub workflows](../.github/workflows).
It contains development/runtime helpers, CI gates and isolated library consumers;
it is not a Maven reactor module or an application dependency. Production
installation and activation belong to [packaging](../packaging/README.md).

## Structure

| Location | Responsibility |
|---|---|
| [dev/](dev/README.md) | Environment checks, synthetic inputs, owned runtime workspaces, inspection and opt-in smoke/load tools |
| [ci/](ci/README.md) | Noninteractive quality gates, evidence receipts and library bundle/publication tooling |
| [fixtures/](fixtures/README.md) | Seven standalone Maven consumers for six reusable libraries; outside the reactor |
| [tests/](tests/README.md) | Shell-tool and offline publication/recovery contract tests |
| [tool-versions.env](tool-versions.env) | Download version and architecture-specific SHA-256 pins used by local lychee bootstrap |
| [eclipse-jdt.prefs](eclipse-jdt.prefs) | Narrow IDE policy for unchecked null conversions at generic/third-party annotation boundaries |

JDT suppresses only `nullUncheckedConversion`; real and potential null-access
checks remain enabled. VS Code Java can load the preferences using a checkout-local
absolute path:

```json
{
  "java.compile.nullAnalysis.mode": "automatic",
  "java.settings.url": "/absolute/path/to/ioc-extractor/tools/eclipse-jdt.prefs"
}
```

## Environment and first checks

Run commands from the repository root:

```bash
make help
make context
make doctor-core
make doctor-ci
```

GNU/Linux with Bash, GNU coreutils/findutils and `/proc` is the supported script
environment. Use WSL, a Linux VM or a Linux container on other hosts. Java 21+
and the committed Maven wrapper are required; do not infer a full compiler or
`javadoc` installation from doctor's Java-version check. Runtime inspection and
scenarios additionally use `curl`, `jq` and/or `sqlite3`; publication tooling uses
Python 3 and GnuPG 2. See each directory reference for command-specific needs.
`NO_COLOR` disables developer-message colors; captured output is color-free.

`make bootstrap` explicitly downloads checksum-verified lychee to
`.dev/tools/bin`; Linux x86_64 and aarch64 are supported. Docs checking prefers
`LYCHEE_BIN`, then `PATH`, then that local binary. CI leaves do not install tools.

## Quality commands and evidence

| Command | Scope and output |
|---|---|
| `make verify` | Deterministic reactor gate; local `last-verify.env` receipt |
| `make pmd-analysis` | Separate adopted PMD source policy; `last-pmd.env` receipt |
| `make pmd-watchlist` | Deferred-rule review; separate reports, no regular PMD freshness update |
| `make lint-shell` | ShellCheck plus packaging and tools shell contracts |
| `make docs` | Offline local-link checking; no external URL availability claim |
| `make library-publication-test` | Offline Python contracts, including disposable-key signing |
| `make dependency-analysis` | Advisory dependency hygiene after main/test compilation; not the security gate |
| `make spotbugs-baseline-proposal` | Non-accepting diagnostic delta from existing raw reports |
| `make mutation-pilot`, `make stability-pilot SEED=42 REPEAT=3` | Opt-in diagnostic pilots with their own reports |
| `make pre-push` | Sequential PMD, verify, shell contracts and docs leaves |

`pre-push` does not run the separate library-consumer CI jobs, Codecov reporting,
scheduled Dependency-Check or opt-in pilots. Consult the
[CI reference](ci/README.md) and workflow for the evidence required by a change.
The [build-quality capability](../docs/dev/build-quality.md) and
[testing policy](../docs/TESTING.md) own analyzer, coverage and test contracts.

A quality claim requires a passed result for the intended commit and current
worktree. `make context` reports verify and PMD freshness independently. Receipts
fingerprint HEAD, tracked diffs and nonignored untracked file contents; generated
and ignored files are outside that fingerprint. Editing source/docs during a run
invalidates its receipt. Local evidence under `.dev/state/` is not a CI check or
release qualification record. A successful `verify` does not refresh PMD evidence.

Dependency hygiene compiles tests with `-DskipTests`; replacing it with
`-Dmaven.test.skip=true` removes test bytecode from analysis. The fuller opt-in
path is `./mvnw -B -ntp -T 1C -Pdependency-analysis verify`.

The SpotBugs proposal writes `target/build-quality/spotbugs-baseline-proposal.xml`
without changing the accepted registry. Run it immediately after the failing
SpotBugs gate while raw reports exist. It supplies analyzer identities and stale
acceptance IDs, not ownership, rationale or permission to accept a finding.

## Effects and publication boundaries

Developer runtime helpers validate normalized paths strictly below repo-local
`.dev/`; reset deletes the selected workspace. Default fixtures also go under
`.dev/`. Explicit fixture output paths may point elsewhere, and `app.sh` without
`--workspace` runs in the checkout root. Use the documented isolated facade and
synthetic configuration; workspace selection does not prevent explicit application
configuration from redirecting storage or transport to other locations.

`make security-update` explicitly refreshes NVD data using `NVD_API_KEY` from
the environment. `make security-scan` disables vulnerability-data updates and uses
the existing database; `make security-report` only lists retained reports. An
old database does not establish current vulnerability status.

`make library-bundle` creates a new unsigned bundle; `make library-consumer`
resolves it in temporary projects with empty Maven caches. Even local consumers
need network access for public dependencies/plugins. Signing and remote writes
are separate publication-tool commands governed by the
[publication guide](../docs/guides/library-publication.md) and
[release policy](../docs/RELEASE-PROCESS.md). Use a separate bundle path per
component; ordinary developer builds do not publish artifacts.
