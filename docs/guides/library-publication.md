# Publishing the concurrency library

The pilot artifact is `io.github.xorex-lc:ioc-platform-concurrency`, Java 21,
with no runtime dependencies. Maven Central is primary; GitHub Packages is a
second destination for the identical release. See
[ADR 0028](../ADR/0028-pilot-library-publication.md), its release-branch
[ADR 0029](../ADR/0029-release-branch-library-publication.md), and the
[API reference](../../platform/platform-concurrency/README.md).
No version is claimed published until the repository checks below pass.

## Local packaging qualification

Use a complete JDK 21 (including `javadoc`), Python 3, GnuPG 2, the Maven wrapper and
network access for build tools and the empty-cache consumer:

```sh
make library-publication-test
make library-bundle
make library-consumer
```

`LIBRARY_VERSION` defaults to `0.3.0-SNAPSHOT`; `LIBRARY_BUNDLE` defaults to
`.dev/library-bundle`. The output must not already exist. Choose another output
path for a new build. This creates the main JAR, sources, Javadoc and a flattened
POM without the private reactor parent. The consumer copies its project outside
the checkout, uses an empty Maven cache, and compares all four resolved artifacts
to the bundle manifest. It checks FIFO, queue rejection, independent keys,
shutdown and synchronous nested execution/failure propagation.

The fixture is a packaging/API consumer, not a feeds-collector implementation.
Its Maven plugins resolve from Central independently of the selected library
repository. These checks need network access and do not replace `make verify`
or `make pmd-analysis`. CI runs them in a separate unprivileged job.

## Account and workflow setup

The owner supplies the verified `io.github.xorex-lc` Central namespace and the
GitHub Environment **`LIBRARY PUBLISHING`**. Store these environment secrets:

- `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`: Central user-token pair.
- `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`: armored signing key and passphrase.

The configured public fingerprint is
`F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9`. Signing verifies the imported key
against it. The reported key expiration is `2027-03-06T12:39:38Z`; the Central
token expires on `2027-03-07`. Renew before those dates and review fingerprint
changes explicitly. Publish the public key to a supported keyserver and verify
that Central can retrieve it. Never put private keys or token values in Git,
workflow inputs, command-line properties or evidence attachments.

Before the first live run, configure `LIBRARY PUBLISHING` with required reviewer
`xORex-LC` and **Selected branches and tags** matching `release-*`. Keep
prevent-self-review disabled while there is only one maintainer; otherwise the
person starting the run cannot approve it. Revisit that setting when a second
trusted maintainer joins. Do not select **Protected branches only** while the
repository has no branch-protection rules: GitHub treats that state as allowing
all branches. Environment secrets remain unavailable until its review passes.

Use default-branch protection as the project governance permits. GitHub requires
the workflow file to exist on the default branch before it can be dispatched
manually. Select the matching `release-X.Y.Z` branch when running it; `main` and
development branches also fail workflow admission. Credential validity and the
first protected run still need live qualification. GitHub
publishing uses the repository's `GITHUB_TOKEN` with `packages: write`; a separate
publishing PAT is unnecessary. Existing `SECURITY CHECKS` remains independent.

## First release

1. Merge the implementation into `release-X.Y.Z` and pass its normal CI gates.
   Add the publication workflow file to `main` so GitHub registers the manual
   workflow; this does not move application code into `main`.
2. Create an annotated `ioc-platform-concurrency-vX.Y.Z` or
   `ioc-platform-concurrency-vX.Y.Z-rc.N` tag on the reviewed release-branch
   commit. The first proposed RC is
   `ioc-platform-concurrency-v0.3.0-rc.1`. Do not move a tag after preparing a
   bundle. Library and product versions remain aligned even though their tags
   and release moments are separate.
3. Dispatch **Library publication**, select the corresponding
   `release-X.Y.Z` branch, enter that component tag, and keep
   `operation=validate`. Leave recovery inputs empty. The job builds
   and verifies without publishing credentials, runs the independent consumer,
   signs once, and retains `signed-library` before contacting either repository.
4. Download and preserve `signed-library` and `central-deployment` artifacts.
   Record the source SHA, run ID, manifest hashes and Central deployment ID.
   `VALIDATED` means staged successfully; it does not mean publicly released.
5. Dispatch again against the same release branch with the same tag,
   `operation=publish`, the original `resume_run_id` and
   `central_deployment_id`. The workflow verifies recovery provenance and
   source/version identity, reuses the signed bundle, publishes Central,
   mirrors missing files to GitHub Packages, then resolves each repository
   separately using fresh consumer caches.
6. Retain both repository URLs, the successful consumer logs and artifact hashes
   in release evidence. Only these live downloads close publication qualification.

A direct `operation=publish` without recovery inputs is supported for an
explicitly approved new release; it performs the same build and retention steps
before publication. Default validation is preferable for the first release.

## Partial failure and recovery

Never repair an existing version with a new build or new signatures. Use the
original signed artifact and Central deployment ID. Artifacts expire after
90 days in Actions, so preserve them externally with the release evidence.
A normal rerun of the old job is not the recovery interface: dispatch a new run
with recovery inputs. A failed run is an acceptable artifact source if it is
from this workflow on the same release branch and has completed.

If Central accepted an upload but the response was lost, find the deployment in
Portal by the artifact/version/manifest-hash name before retrying. If validation
failed, inspect Portal diagnostics and retain the deployment. If bytes must
change, prepare a new version/tag rather than replacing a released version.
A partially visible Central release stops until propagation completes; matching
published bytes are skipped. GitHub upload compares existing files first and
only sends missing files; any mismatch stops the operation. A successful upload
without both consumers is still incomplete qualification. The first live run
must establish registry behavior, signing acceptance and package visibility.

## Consumption

After a version has passed publication qualification, add:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-concurrency</artifactId>
  <version>0.3.0</version>
</dependency>
```

This is an example coordinate, not a claim that `0.3.0` is already available.
Central needs no additional repository configuration. For GitHub Packages use
`https://maven.pkg.github.com/xorex-lc/ioc-extractor` and matching Maven server
credentials with package read access. Even public GitHub Maven packages require
authentication; use the Central coordinate for ordinary public consumption.

Authoritative service documentation: [Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/)
and [GitHub Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).
