# Release Process

This document defines the project-wide contract for versioning, building,
publishing, deploying, and maintaining ioc-extractor releases. It applies to
the whole Maven reactor and to the single bootable application produced by
`bootstrap/ioc-app`.

Project-wide security principles, control states, vulnerability disposition,
suppression rules, and maturity roadmap are defined in
[SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md). This release process
consumes those controls; it does not redefine them.

The process has four goals:

1. one authoritative product version;
2. an immutable mapping from a released version to source and binary bytes;
3. a repeatable quality gate before publication;
4. enough runtime metadata to identify and support a deployed build.

## Release identity model

Several identifiers describe different things and must not be collapsed into
one value:

| Identifier | Example | Meaning | Authority |
|---|---|---|---|
| Product version | `0.1.1` | User-visible software release and compatibility statement | Maven `${revision}` |
| Source tag | `v0.1.1` | Immutable source commit selected for the release | Git |
| Source revision | `0f60b0ad990d...` | Exact source tree used by a build | Git commit |
| Binary digest | SHA-256 | Exact released bytes | Release workflow |
| Build metadata | version, commit, build time | Runtime support and traceability data embedded in the application | Maven/Spring Boot build |
| Deployment ID | commit plus timestamp | One activation of a build on a host | Deployment tooling |
| Schema/protocol version | migration or manifest version | Compatibility of a specific durable or wire contract | Owning subsystem |

The product version is not a substitute for a Git commit or binary digest.
Likewise, a deployment ID is not a product version.

## Product version authority

ioc-extractor is one product released from a lockstep multi-module Maven
reactor. All reactor modules therefore share one product version. Modules do
not receive independent versions unless they become independently published
and acquire separate compatibility lifecycles.

The root `pom.xml` owns the only editable product version through Maven's
CI-friendly `${revision}` placeholder:

```xml
<version>${revision}</version>

<properties>
    <revision>0.2.0-SNAPSHOT</revision>
</properties>
```

Every child POM references the reactor parent with `${revision}`:

```xml
<parent>
    <groupId>com.iocextractor</groupId>
    <artifactId>ioc-extractor-parent</artifactId>
    <version>${revision}</version>
    <relativePath>../../pom.xml</relativePath>
</parent>
```

Inter-module dependencies continue to use `${project.version}` through the
root `dependencyManagement`. `${revision}` must not replace
`${project.version}` in dependency declarations.

There is no separate `VERSION` file. Runtime resources, release assets,
installer metadata, documentation examples, and tests must derive their
version from the Maven project or from the built artifact. A second editable
copy of the product version is a defect.

CI-friendly versions require flattened consumer POMs when individual reactor
modules are installed or deployed for consumption outside the reactor. The
project does not publish those modules today. If that changes, introduce and
verify `flatten-maven-plugin` as part of the repository-publication design; do
not add it merely for the bootable application build.

## Versioning policy

Versions follow Semantic Versioning syntax:

```text
MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]
```

During the `0.x` development line, the project uses this explicit policy:

- `PATCH` is a compatible bug fix, security fix, documentation correction, or
  internal hardening change that preserves supported external contracts.
- `MINOR` adds a substantial capability or intentionally changes a supported
  external contract. During `0.x`, incompatible contract changes normally
  increment `MINOR`.
- `MAJOR` marks a stable public contract from `1.0.0` onward; after that point,
  incompatible supported-contract changes increment `MAJOR`.
- A release candidate uses `X.Y.Z-rc.N` and is never retagged as the final
  release.
- A normal development version ends in `-SNAPSHOT`.
- A final release version has no `-SNAPSHOT` or prerelease suffix.

The supported external contract includes more than Java APIs:

- CLI commands, options, output intended for automation, and exit codes;
- `ioc.*` configuration keys, validation, defaults, and migration rules;
- generated artifact, export manifest, and control-event schemas;
- documented ECS fields and their JSON scalar types;
- health and operational endpoints;
- supported storage upgrade and rollback behavior;
- installer and deployment interfaces used by operators.

Internal refactoring does not determine the version by size. The observable
contract and migration impact do.

Subsystem versions such as SQLite migrations, export manifests, diagnostic
catalogs, control events, and artifact identity epochs remain independently
owned. They must not be forced to match the product version.

## Runtime build information

The bootable application must expose build identity without duplicating the
version manually:

- `spring-boot:build-info` generates `META-INF/build-info.properties` from the
  Maven project;
- Actuator `/actuator/info` exposes available build information in daemon mode;
- the lightweight `ioc --version` path reports the product version without
  starting Spring;
- ECS `service.version` is filtered from `@project.version@` during the Maven
  resource build;
- release/deployment metadata records the Git commit and build time;
- the published SHA-256 digest identifies the exact released jar bytes.

The `build-info` execution belongs only to `bootstrap/ioc-app`. A normal local
build omits commit metadata instead of inventing an `unknown` value. A build
that already knows the immutable source revision injects the full SHA
explicitly:

```bash
./mvnw -B -ntp -T 1C -Dbuild.commit=<full-git-sha> clean verify
```

The `embed-build-commit` Maven profile is activated by that property and writes
it as `build.commit`; Maven Enforcer rejects a non-full or non-hexadecimal Git
object ID. The application never invokes Git at runtime and does not depend on
a `.git` directory being present.

The intended operator-facing shape is:

```text
ioc-extractor 0.1.1
commit: 0f60b0ad990d
built: 2026-07-17T15:00:00Z
```

The version command may omit unavailable optional metadata in an IDE or
unpackaged test run, but it must never invent fallback release data.

Build metadata must not contain credentials, local source paths, or a dirty
working-tree diff. A dirty local deployment may be labelled as dirty by the
deployment ID, but a published release is always built from a clean tagged
commit.

## Branches and tags

Git tags, not branch names, are the authority for published releases.

- `main` represents the current integration line.
- A release branch may be used temporarily for stabilization and release-only
  fixes. It is not proof that a release exists.
- Maintenance branches may be introduced only when more than one supported
  line needs backports.
- Final releases use annotated tags named `vX.Y.Z`.
- Release candidates use annotated tags named `vX.Y.Z-rc.N`.
- A published tag is immutable. Never move, delete, or reuse it to replace
  released bytes.
- A correction after publication receives a new version and a new tag.

Tag protection or repository rules should prevent accidental updates to
`v*` tags. Required CI checks should protect integration and release branches.

## Release artifacts

The minimum published release set is:

| Artifact | Required content |
|---|---|
| `ioc-extractor-X.Y.Z.jar` | Bootable application built from the tagged source |
| `ioc-extractor-X.Y.Z.jar.sha256` | SHA-256 digest of that exact jar |
| Release notes | User-visible changes, upgrade notes, and known issues |
| Git tag `vX.Y.Z` | Source identity for the release |

Source archives generated by the repository host are supplementary; they do
not replace the tested bootable jar.

An SBOM, signatures, and build attestations are recommended future supply-chain
extensions. They become mandatory only after the project defines their
consumer and verification workflow.

The release workflow builds the jar once. The same bytes are attached to the
release and promoted to deployment targets. Rebuilding separately for a test
stand, release page, and production would create different binaries carrying
the same product version and is prohibited.

## Release notes contract

Release notes are curated for users and operators. They are not a dump of Git
subjects, ADRs, or `KNOWN-ISSUES.md`. Automated notes may be used as a draft,
but a maintainer reviews the public impact.

Use this structure when a section is applicable:

```markdown
# ioc-extractor X.Y.Z

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

Each breaking or operationally observable change states:

1. what changed;
2. who is affected;
3. whether data/configuration is migrated automatically;
4. what the operator must do;
5. whether rollback remains supported after migration.

`KNOWN-ISSUES.md` remains the complete engineering debt registry. Release notes
contain only issues relevant to users of that release. ADRs remain the immutable
decision history and are linked only when their rationale helps an operator or
maintainer.

## Release lifecycle

### 1. Plan and stabilize

1. Select the intended version using the compatibility policy above.
2. Define the release scope and explicitly defer unrelated work.
3. Ensure release-blocking issues have owners and acceptance criteria.
4. Review upgrade, rollback, storage migration, configuration, logging, and
   packaging impacts.
5. Enter the release line as `X.Y.Z-SNAPSHOT` until the final release commit.

### 2. Prepare the release commit

1. Set `${revision}` to final `X.Y.Z`.
2. Update release notes and affected published documentation.
3. Remove stale hard-coded version examples or derive them from the build.
4. Verify `ioc --version`, `/actuator/info`, ECS `service.version`, the jar
   name, and packaging metadata agree.
5. Confirm the working tree is clean and the release commit is pushed.

### 3. Run the quality gate

The minimum repository gate is:

```bash
./mvnw -B -ntp -T 1C verify
git diff --check
```

Also run release-specific checks:

- a successful manual `Dependency Security` workflow on the exact candidate,
  as required by [SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md),
  commit, with no untriaged Critical/High findings and no unused suppression
  rules;
- documentation link validation;
- shell syntax checks for packaging scripts;
- rendered systemd unit validation;
- lightweight CLI help and version commands;
- oneshot extraction/export smoke;
- daemon startup, actuator health, and build-info smoke;
- upgrade and rollback smoke against representative durable state;
- stand checks required by accepted ADRs or release notes.

A skipped environment-dependent check is recorded in the release notes; it is
not silently treated as passed.

### 4. Create the source release

After required checks pass:

```bash
git tag -a vX.Y.Z -m "Release X.Y.Z"
git push origin vX.Y.Z
```

The tag must point to a commit whose effective Maven version is exactly
`X.Y.Z`. The tag is created only after the branch commit has passed required
CI; the tag workflow verifies the contract again.

### 5. Build and publish once

The tag-triggered release workflow:

1. checks out the exact tag;
2. verifies `tag == v${project.version}`;
3. rejects `-SNAPSHOT` and dirty/generated differences;
4. runs one complete Maven `verify` invocation, which also builds the bootable
   jar;
5. verifies embedded version/build metadata;
6. computes SHA-256;
7. creates or updates a draft release;
8. uploads the jar and checksum;
9. publishes the release only after all required assets and notes exist.

The release job receives only the permissions required to create release
assets. Pull-request validation jobs do not receive release write permission.

### 6. Deploy and verify

Deployment consumes the published jar and verifies its checksum before
activation. The existing immutable release directory, SQLite backup, health
gate, and rollback protocol remain the deployment authority.

The standalone installer takes the exact artifact through `--jar` and consumes
an explicit `--checksum` or an adjacent `<jar>.sha256`; a mismatch fails before
host mutation. Optional autodiscovery accepts exactly one candidate and never
selects by filename version or modification time. Local checkout deployment
uses a commit-and-time deployment ID, builds from `clean verify`, and rechecks
the expected artifact SHA-256 after crossing the privileged boundary.

After activation, record and verify:

- product version;
- Git commit and deployment ID;
- jar SHA-256;
- `/actuator/info` build identity;
- storage and artifact health;
- known external dependency degradation that is intentionally outside the
  deployment rollback gate.

### 7. Close and advance

1. Mark the release milestone complete.
2. Link the tag, published artifacts, checksum, and verification evidence.
3. Merge or backport release-only fixes according to the active branch model.
4. Move the development line to the next intended `-SNAPSHOT` version.
5. Do not rewrite the released tag or its assets.
6. Monitor the first deployment window and open a new patch release for any
   required correction.

## Rollback and hotfix policy

Rollback restores the previous immutable application release and the matching
pre-upgrade SQLite snapshot according to the packaging protocol. Before a
release, every schema migration must state whether rollback requires restoring
the database snapshot rather than only switching the application symlink.

If a released defect needs correction:

1. reproduce and classify it;
2. create a fix from the appropriate supported line;
3. run the normal release gate;
4. publish a new patch version and tag;
5. document whether already-upgraded state needs remediation.

Never replace the jar attached to an existing release version, even if the
original artifact is known to be defective.

## Definition of Done

A version is released only when all applicable statements are true:

- one final non-SNAPSHOT Maven version is used by the whole reactor;
- required CI and release-specific gates passed;
- published docs and release notes describe observable changes and upgrade
  actions;
- the tag points to the verified release commit;
- runtime version/build identity matches the tag;
- the released jar was built once from that tag;
- its SHA-256 is published and verified;
- the release contains no known unrecorded blocker;
- deployment and rollback evidence exists for the supported installation path;
- the next development version is selected after publication.

## Deferred automation

The following are deliberate follow-up improvements, not hidden release
claims:

- generated SBOM and provenance attestations;
- artifact signing and signature verification in deployment;
- automated changelog aggregation from a stricter commit convention;
- independently versioned/published Maven modules and flattened POMs;
- multiple maintained release lines with automated backport tracking;
- reproducible-build comparison across independent runners.

They should be activated when a real consumer or support requirement justifies
their lifecycle cost.
