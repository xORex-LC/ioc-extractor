# Release Process

[Русская версия](ru/RELEASE-PROCESS.md)

This document defines standing project-wide rules for preparing, versioning,
qualifying, publishing and maintaining releases of the application and admitted
reusable libraries. Apply it to every release, selecting the supported scope
and relevant checks before work begins.

## Policy and evidence ownership

| Owner | Content |
|---|---|
| This policy | Release identity, compatibility, admission, promotion, recovery and completion rules |
| Release notes and component release records | A version's changes, operator/consumer impact, upgrade actions, limits and evidence references |
| Candidate execution evidence | Exact source, bundle hashes, runs, target qualification, blockers and reviewed dispositions |
| POM, workflows and tooling | Executable version authority, triggers, permissions, assets and validation |
| Capability docs and guides | Detailed subsystem migrations, operation and publication procedures |
| ADR | Accepted decisions and their historical context |

Keep candidate progress, measured results and individual publication incidents
with their records. Update this policy when a project rule changes. A new
reusable component follows the same admission rules; it does not require copying
its release history or a changing component inventory into this document.

[TESTING.md](TESTING.md) owns test/coverage policy;
[SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md) owns security controls,
vulnerability disposition and suppression policy. This process consumes those
requirements. Their implementation status must be supported by evidence, not
inferred from a policy statement or workflow name.

## Release identity model

Keep these identities distinct:

| Identity | Meaning | Authority |
|---|---|---|
| Product version, `X.Y.Z[-rc.N]` | Application compatibility statement | Effective Maven product version |
| Application tag, `vX.Y.Z[-rc.N]` | Immutable selected source | Annotated Git tag |
| Library coordinate | Group, artifact and exact component/family version | Admitted component POM and bundle |
| Component tag | Immutable source for one library artifact | Protected publication admission |
| Source revision | Full Git object ID used by a build | Resolved source commit |
| Bundle/digest | Exact qualified files and their hashes | Build manifest/checksum evidence |
| Runtime build information | Version, source commit and build time | Packaged application metadata |
| Deployment ID | One activation on a target | Deployment tooling and target evidence |
| Schema/protocol version | A durable or wire-format contract | Owning subsystem |

The chain is source selection → build → qualified bytes → publication → target
activation. Compare each link with its authority. A version or deployment ID
alone does not identify binary bytes, and a checksum must be checked against the
intended artifact from the approved distribution source.

Source-level checks belong to the candidate commit; binary/stand checks belong
to the exact bundle and target configuration. Rebuilding changes the candidate
bundle unless byte equality is established. Earlier binary evidence must not be
silently attached to different bytes.

## Product version authority

The application and its internal modules share one product version. Every
release family has one editable version authority; resources, artifact names,
metadata and tests derive from its effective build version. There is no parallel
editable `VERSION` file.

Use the versioning mode admitted by the POMs and publication tooling. The
[publication guide](guides/library-publication.md) describes implementation
availability; accepting independent ownership does not by itself activate an
independent Maven version scheme.

In the shared-version reactor mode, the root owns Maven's CI-friendly revision:

```xml
<version>${revision}</version>

<properties>
    <revision>X.Y.Z-SNAPSHOT</revision>
</properties>
```

Child parent declarations use the same revision:

```xml
<parent>
    <groupId>com.iocextractor</groupId>
    <artifactId>ioc-extractor-parent</artifactId>
    <version>${revision}</version>
    <relativePath>../../pom.xml</relativePath>
</parent>
```

These are contextual POM fragments; replace the illustrative version for an
actual build. Managed reactor dependencies use `${project.version}`, not
`${revision}`, in this mode. A global `-Drevision`/`LIBRARY_VERSION` override
versions the selected shared reactor; it does not independently version a
component. A different mode requires the activation criteria below.

## Reusable library versions

### Release ownership and compatibility

Reusable libraries own compatibility independently of the consuming service.
The [library catalog](guides/libraries/README.md), component contracts and
[ADR 0048](ADR/0048-independent-library-versions-and-reuse.md) identify owners
and coupled release families. Exact-version core/adapter families advance
together: releasing either member releases the declared family, including an
unchanged partner. Each artifact retains its own source/tag, bundle and qualification.
The required core is qualified before its adapter.

An unchanged library does not need a release merely because the service changes.
Consumers select exact qualified versions and own upgrades. Repository location,
parent build settings and publication credentials do not define compatibility.

Compatibility includes source/binary API, validation and failure behavior,
thread safety/ordering, resource ownership, emitted names/types and minimum
Java/framework requirements. Review package moves, dependency upgrades and
security fixes for observable impact as carefully as new APIs.

Published versions, including RCs, are immutable. A correction uses a new version
and component tag. Recovery reuses original signed files and deployment identity;
it does not rebuild, re-sign, overwrite or retag an existing coordinate.

### Library changes required by a service feature

Inspect the existing API and callers first. Reuse a suitable mechanism or extend
its owning library for a focused shared capability. Service rules, catalogs,
schemas, composition and recovery policy stay with the service; follow the
[development guide](guides/libraries/development.md).

Library and consumer changes may be developed together. Under independent
versioning, advance the changed library/family's development version before
changing released content. Under shared versioning, use the admitted common
development version and record the intended component/compatibility change.

Before release, compare against the selected published baseline, add meaningful
regressions, update public Javadoc and paired guides, run applicable build/
analyzer checks and isolated packaged consumers. A consumer fixture is a bounded
compatibility witness, not exhaustive source/binary API comparison; implementation
gaps belong in [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

Publish and qualify required library content before releasing the consuming
service. Pin the selected released version and verify the final integration.
Local reactor sources must not silently replace released content under the same
coordinate. Local/SNAPSHOT packaging is development evidence, not proof of
published dependencies. Release/publication authorization remains explicit.

### Activation of independent versions

A transition from shared to independent source versions needs a reviewed
implementation that proves:

1. One distinct version authority per library/family and product; parent
   resolution, reactor ordering and dependency management work with differing
   versions, while unchanged families keep their versions.
2. Agreement between component tag, source ancestry, build version, flattened/
   embedded POMs, dependency closure and recovery. Protected admission does not
   assume a component version equals the product release branch.
3. Exact-version core/adapter coupling, core-first repository qualification and
   original signed-core handoff/recovery remain intact.
4. Service builds select intended released content even with local library
   sources present. Qualification detects changed source under a released
   coordinate; development sources use their next version.
5. Product-only and library-only scenarios, isolated consumers, selected origin,
   hashes and negative version/dependency cases pass without changing existing
   publication identities.

Update owning POM/tooling, consumer fixtures, guides, module references and
local guidance together. Until that scheme is implemented and qualified, keep
the admitted shared-version behavior. Track transition status in the
[publication guide](guides/library-publication.md) and
[issue registry](KNOWN-ISSUES.md), rather than treating this policy as execution
evidence.

## Versioning policy

Versions use Semantic Versioning syntax:

```text
MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]
```

Apply the following compatibility rules to the product or library release family:

| Change | Increment |
|---|---|
| Compatible bug/security fix, documentation correction or internal hardening | PATCH |
| Compatible supported capability/API addition | MINOR |
| Incompatible supported-contract change during `0.x` | MINOR, with migration notes |
| Incompatible supported-contract change from `1.0` onward | MAJOR, with migration notes |

`-SNAPSHOT` marks development. RCs use immutable `X.Y.Z-rc.N` coordinates;
the eventual final version is a separate release, not a moved tag. A final
version has no prerelease suffix. An RC does not guarantee compatibility with
the later final release. Each library/family needs its own explicit stable-
contract decision before `1.0.0`.

Application automation admits the narrower tag subset `vX.Y.Z` or
`vX.Y.Z-rc.N`, with positive RC numbers; broader SemVer syntax does not imply
workflow support for every suffix/build-metadata form.

Supported application contracts include CLI/options/output/exit codes,
configuration/defaults/preflight, artifacts/manifests, documented event/logging
formats, health endpoints, storage upgrades/rollback and installation interfaces.
Internal change size does not determine the version; observable impact does.
Prose-only guide corrections do not inherently require a new library binary.

### Deprecation and removal

1. Announce the deprecated contract, replacement and migration window in release
   notes; provide a stable actionable runtime warning where the surface permits.
2. Normally support old and replacement contracts together for at least one
   published release, or the explicitly announced alternative window.
3. Remove in a later release as a breaking/observable change. Removed
   configuration keys fail preflight with a migration hint rather than silently
   becoming no-ops.

Emergency removal that skips the window needs an explicit security/data-integrity reason, breaking-change
disposition and operator/consumer action. Subsystem schema, manifest, catalog,
event and identity versions remain separately owned; do not force them to equal
the product version.

## Runtime build information

The application derives identity from the build:

- `spring-boot:build-info` in `bootstrap/ioc-app` generates
  `META-INF/build-info.properties`.
- Daemon `/actuator/info` exposes available build data.
- Lightweight `ioc --version` reports identity without starting Spring.
- Maven filters ECS `service.version` from `@project.version@`.
- Release/deployment evidence links full source revision, build time and digest.

A build that knows the immutable revision supplies `build.commit`.
The `embed-build-commit` profile validates a full hexadecimal Git object ID
and embeds it; a normal build without it omits the optional field. Runtime does
not invoke Git or require `.git`. The packaged shape is:

```text
ioc-extractor X.Y.Z
commit: <first-12-hex-digits>
built: <UTC-build-instant>
```

Optional unavailable metadata may be absent in IDE/unpackaged use; do not invent
fallback release facts. Metadata must contain no credentials, local source
paths or dirty diff. A dirty development deployment may carry an explicit dirty
identity, but published releases originate from clean selected source.

## Branches and tags

Tags select release source; branch names describe workflow.

- Integration, stabilization and maintenance branches have explicit owners and
  support/backport purposes. A release branch is not publication evidence.
- Application final/RC releases use annotated `vX.Y.Z`/`vX.Y.Z-rc.N` tags.
- Component tags follow their protected publication admission and select that
  artifact's qualified source.
- Never move, delete or reuse a published tag. Corrections get a new version/tag.
- Required checks/reviews protect eligible source; tag/environment protections
  prevent unauthorized promotion.

Verify effective external repository settings before claiming their enforcement;
workflow text alone cannot prove branch/tag protection.

## Release artifacts

| Family | Required identity and content |
|---|---|
| Application | Annotated source tag, curated notes, `ioc-extractor-X.Y.Z.jar` and its `.sha256` sidecar |
| Reusable library | Admitted coordinate/tag, parent-independent POM, main/sources/Javadoc and license, manifest/checksums, required signatures and repository qualification |

The component descriptors and [publication guide](guides/library-publication.md)
own exact library files/dependencies. Source archives supplement the tested
application JAR; they do not replace it. Additional application signing, SBOM or
attestation controls become mandatory when adopted with a defined trust/consumer
and verification workflow. Library repository signing requirements remain scoped
to their publication contract.

Build one qualified bundle and promote its exact bytes across draft, release,
stand and production. Multiple development builds are allowed; each newly built
bundle needs its own applicable binary evidence. Same source/version does not
establish reproducible bytes across runs.

## Release notes contract

### Application notes

Copy [TEMPLATE.md](../.github/release-notes/TEMPLATE.md) to
`.github/release-notes/<application-tag>.md`. The tag workflow reads the file
from selected source. Keep each required section in order; use an explicit
`Not applicable` disposition when no content applies:

```markdown
# IOC Extractor X.Y.Z

## Highlights
## Added
## Changed
## Fixed
## Upgrade notes
## Breaking or observable contract changes
## Known issues
## Verification
## Artifacts and checksums
```

An optional `Technical references` section may follow. Notes explain user/
operator outcomes, not Git subjects, ADR contents or the entire debt registry.
For each observable/breaking change state what changed, affected users, automatic
migration, required action and rollback support. Explain prerequisites, the
backup/recovery unit and post-upgrade validation.

For machine-readable changes include minimal before/after payloads and parser/
query examples where relevant. Distinguish field/path, physical shape, scalar
type and presence/value semantics; unchanged names alone do not imply compatibility.

Record actual verification scope, unavailable external checks and approved
dispositions. Put exact final commit/digest/run identities in produced evidence;
do not guess them before the candidate exists. Link user-relevant known issues
and mitigations. Never include secrets or sensitive operator data.

Prefer PR/issue references, then ADR rationale, one full compare range, and a raw
commit only when its exact transition adds useful evidence. The tag, packaged
identity and digest carry release identity; per-bullet commit links are unnecessary.

`make release-notes-context PREVIOUS_TAG=vX.Y.Z TARGET_REF=HEAD` produces a
read-only inventory; `GITHUB=1` adds eligible merged PRs. Treat generated
inventories and notes as curation inputs, with human review of compatibility
and operator impact. Historical imports must be explicitly labelled and must
not rewrite immutable tags or already-published notes; archive exceptions are
documented in the [notes reference](../.github/release-notes/README.md).

### Component release records

Library releases have component-scoped records, not application titles or a
forced copy of the application template. Identify coordinate/family, source/tag,
supported API/dependencies, compatibility/migration, original manifest/signed
bundle, repository consumers and any incomplete qualification/recovery.

Use the [catalog](guides/libraries/README.md) and
[publication guide](guides/library-publication.md) to find those records and their
naming conventions. A record must distinguish original publication, recovery
and later consumer evidence without changing immutable identities.

## Release lifecycle

### 1. Plan and stabilize

Select product/component/family scope, intended version and supported upgrade
paths. Assign blockers, acceptance criteria and reviewed deferrals. Review all
observable surfaces, migrations and rollback boundaries. Keep development on its
admitted `-SNAPSHOT` scheme until preparing a candidate.

### 2. Prepare the release commit

Set the exact final or RC version, update affected docs and notes, and reconcile
version-derived metadata/assets. Identify required libraries and qualify their
selected content before the consuming product is released. Commit clean source
and make it available to required CI; candidate progress belongs to its evidence.

### 3. Run the quality gate

Use the complete adopted controls, not a selected fast suite:

```bash
make verify
make pmd-analysis
make docs
make lint-shell
git diff --check
```

[TESTING.md](TESTING.md) and [build quality](dev/build-quality.md) own coverage,
analyzer policies, report integrity and freshness. Qualify the exact candidate
source; after changes, rerun affected checks and the final required gates.

Also obtain applicable security evidence under the
[security policy](SECURITY-ENGINEERING.md), packaged identity/help/version and
runtime smoke, supported migration/rollback evidence, target-specific external
checks and library compatibility/packaged/repository consumers. Define
applicability before release and record an explicit disposition for unavailable
checks. Advisory reporting does not substitute for blocking project controls.
Reviewed dispositions apply only where the owning contract permits them;
a failed blocking gate remains blocking until fixed or explicitly changed
through the project's policy/scope decision process.

Source gates may build development artifacts. Final binary qualification must
refer to the bundle actually promoted. Do not infer that one release workflow
automates every independent security, analyzer or stand requirement.

### 4. Create the source release

After required source checks pass, create the admitted annotated tag on the
selected commit. Its version must agree with the effective build version.
Preserve tag/source identity throughout publication and recovery.

### 5. Build and publish once

The [application workflow](../.github/workflows/release.yml) resolves exact
source/tag/version, verifies annotation and commit selection for draft creation,
injects source identity, runs one `clean verify`, rejects source-tree changes,
and verifies packaged build metadata plus lightweight version output. It
prepares the JAR/checksum once and passes those bytes to a separate write-enabled
job that performs no Maven build.

A manual build-only dispatch validates/builds the selected ref and retains
workflow artifacts; it does not create a release. Draft mode requires the
named existing tag to select the checked-out commit. Tag pushes follow draft
mode. Retention and exact inputs belong to the workflow.

The [draft helper](../packaging/publish-release-draft.sh) checks supplied
version/files/checksum/notes and creates or repairs only a draft. Source/tag
admission belongs to the workflow. Existing required assets are downloaded and
compared byte-for-byte; a mismatch fails, never clobbers. Published releases are
read-only. A rerun that rebuilds different bytes is not recovery of the original
bundle and cannot reuse its binary evidence.

[Library publication](#library-publication) follows its own protected workflow.
Publication is an explicit authorized promotion after notes, assets and required
evidence have been reviewed; building a bundle or accepting an ADR does not
authorize it.

### 6. Deploy and verify

Qualify the exact candidate bundle on the required stand before publication
where the release plan requires it; promote the same approved bytes to targets.
Use the supported [packaging protocol](../packaging/README.md), verify checksum
before host mutation and compare installed version, full source identity,
deployment ID, digest, build information and local storage/artifact health.

A release version alone does not prove installation-layout, configuration or
schema compatibility. Reconcile operator configuration explicitly, validate it
with the candidate in the effective service environment, and keep the agreed
backup/restore unit. New template files do not automatically migrate installed
configuration.

Checkout-based local deployment builds its own identified bundle; it does not
prove deployment of an already published JAR. Optional remote degradation must
be distinguished from local activation failure according to the deployment
health contract.

### 7. Close and advance

Record authorized publication, immutable tag/assets, required qualification and
remaining dispositions. Merge/backport release-only fixes as supported, select
the next development version, monitor the deployment window and issue a new
version for corrections. Publication state and completion of all release
acceptance obligations remain separately observable.

## Rollback and hotfix policy

Every supported upgrade states its rollback boundary before activation:
binary-only switch, coordinated restore, side-by-side migration or explicitly
unsupported rollback. Qualify that claim against representative previous state;
do not infer support from an additive migration or a healthy startup alone.

An application recovery point includes the matching application, operator configuration,
systemd/unit state and both SQLite databases with a consistent backup procedure.
Keep relevant side files, permissions and ownership as required by packaging.
Never copy a live SQLite file with an ordinary filesystem copy and assume the
result is consistent.

The deployment automation restores only its documented scope. Configuration
reconciliation, moved input files, projections/slices and completed remote side
effects may require separate recovery. Pause intake/sync for rollback-sensitive
qualification; automatic local rollback cannot reverse remote effects.

For a hotfix, reproduce/classify the defect, fix the supported line, run normal
gates, publish a new compatible patch or an appropriate breaking version, and
state remediation for already-upgraded data/configuration. Never replace an
existing version's JAR or Maven files.

## Definition of Done

Close a product/component release when:

- scope, supported contracts and upgrade/rollback paths are explicit;
- one effective version authority exists per product/release family;
- required blocking gates passed and exact-bundle/target qualification satisfies
  its governing acceptance contract, with only permitted reviewed dispositions;
- notes/docs explain observable changes, actions and remaining user impact;
- immutable source/tag, packaged metadata, bundle hashes and repository origin agree;
- required artifacts/signatures/checksums are present and validated;
- publication is authorized and no unrecorded release blocker remains;
- deployment/recovery evidence exists for claimed supported paths;
- next development ownership and post-release follow-up are recorded.

Build, publication and deployment are distinct results. An RC, local consumer,
successful upload or documentation update alone does not establish all of them.

## Deferred automation

New controls need an owner, consumer, verification contract and retained evidence
before becoming release requirements. Examples include application authenticity/
attestation, composition transparency/SBOM, independent version activation,
automated backports, changelog assistance and reproducible-build comparison.
Track their adoption/status with owning implementation and issue records.
Do not describe a planned control as enforced or relax an adopted one silently.

## Library publication

Use the separate [protected workflow](../.github/workflows/library-publication.yml)
and the component descriptors in [publication tooling](../tools/ci/library-publication.py).
The catalog identifies admitted components; adding a library requires explicit
source/version admission, dependency/API scope, package/license/docs checks,
signed immutable bundle, isolated consumers and repository qualification.

Publish identical library files to the required repositories. The project uses
Maven Central as primary and GitHub Packages for the same bytes; verify selected
origin and hashes in each claimed repository. Alternate-cache/repository success
must not mask a missing selected artifact or transitive dependency.

Exact-version families qualify core before adapter, preserve separate artifact
records, and hand off the original signed core during adapter publication/recovery.
A partial publication resumes from original signed files and repository evidence.
A later component's release never changes an earlier artifact's tag, bytes or
qualification history.

Flattened consumer POMs must not require the private reactor parent; validated
third-party dependencies and application-owned backends retain separate owners.
Application release automation does not publish Maven libraries. Detailed
commands, active admission constraints and recovery procedures belong to the
[publication guide](guides/library-publication.md), with decisions in
[ADR 0028](ADR/0028-pilot-library-publication.md),
[ADR 0029](ADR/0029-release-branch-library-publication.md) and
[ADR 0048](ADR/0048-independent-library-versions-and-reuse.md).
