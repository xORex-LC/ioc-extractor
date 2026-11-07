# Developing and versioning reusable libraries

[Русская версия](../ru/libraries/development.md)

Use this guide when a service feature needs one of the
[shared libraries](README.md), including when its current API needs a change.
[ADR 0048](../../ADR/0048-independent-library-versions-and-reuse.md) records the
decision. The project-wide
[release policy](../../RELEASE-PROCESS.md#reusable-library-versions) owns version
rules; the [publication guide](../library-publication.md) owns release operations.

## Current state and accepted direction

Independent library versions are accepted. Concurrency, diagnostics, logging and
configuration will have separate release versions. Events core and the Spring
adapter will initially share one family version, with an exact-version core
dependency and core-first publication. Internal IOC modules keep the service
version. A release of either events member advances both versions and releases
both artifacts, even when one implementation is unchanged. Services choose when
to adopt a library release; an unchanged library does not need a release because
IOC changes.

The technical transition is **not implemented**. Source POMs still inherit the
product's `${revision}`, and publication admission still matches the component
version to `release-X.Y.Z`. Use the existing process until
[TOOL-9](../../KNOWN-ISSUES.md) is completed. A different `LIBRARY_VERSION` or
global `-Drevision` is not an independent-library version mechanism. Existing
`0.3.0-rc.1` artifacts and tags retain their identities.

Today the service reactor still builds these modules from local source at the
shared version. A green reactor build therefore does not prove that it consumed
the published library bytes. Standalone consumers qualify the distribution
separately; service selection of independently versioned released dependencies
still requires the TOOL-9 implementation and acceptance checks.

## Find the existing tool first

Start with the catalog, then read the relevant guide, module README and public
Javadoc. Inspect implementation, tests and actual callers before deciding that
an API is missing. Search for the behavior as well as its likely class name.

For example, a keyed execution requirement should be checked against concurrency;
bounded diagnostic details against diagnostics; typed logging and restoring MDC
against logging; property-name inspection against configuration; and local
control-event publication against events. Check their limits as well: keyed
execution is process-local, event publication is not durable delivery, and
configuration inspection does not choose a service's startup policy.

| Finding | Action |
|---|---|
| Existing API meets the requirement | Use it and test the service integration. |
| API fits, but the service needs its own policy or wiring | Compose it locally and keep that policy in the service. |
| A defect or a focused generic capability belongs to the library | Change the owning library and its consumers together. |
| Requirement is outside the library's responsibility or dependency boundary | Keep it in the service or the appropriate adapter and explain the boundary. |

Do not copy a library class, create a competing helper or add another dependency
just to avoid reading or changing a suitable shared API. Conversely, an existing
library is not a reason to build a speculative abstraction or introduce Spring
into a JDK-only module. Correcting or extending an admitted library does not
require waiting for a second deployed service.

## Develop a library change with a feature

1. State the missing behavior and why the owning library is the right place.
   Identify affected public contracts and existing callers. Preserve service
   catalogs, configuration schemas, business rules, durable state and recovery
   decisions outside the library.
2. Select the next development version according to the release policy. Once
   independent versions are activated, move the changed library or events family
   to its next `-SNAPSHOT` version. Until then, use the current shared development
   version and record the intended component change; do not fake the migration
   with an unrelated product-version override.
3. Implement the library and consumer changes together. One branch and PR are
   allowed. Separate logical commits make the shared contract and its service
   integration easier to review; a separate library PR is optional.
4. Cover supported positive, failure, boundary and recovery behavior. A defect
   gets a regression test. Review source and binary compatibility against the
   chosen published baseline, plus behavior that signatures cannot express.
   Automated API comparison is still open as TOOL-6; passing existing consumers
   is not a complete compatibility comparison.
5. Update meaningful public Javadoc, the module reference and both language
   guides in the same change. Keep runnable blocks aligned. State differences
   from the published release; do not describe a development-only method as
   available in an older artifact. Record upgrade/migration steps when needed.
6. Run focused tests, then `make verify` and `make pmd-analysis` for production
   Java or analyzer-scope changes. Prepare a new local bundle and run the
   relevant isolated consumer before publication, following the publication
   guide. Local packaging is distinct from repository qualification.

No publication is needed merely to continue feature development. SNAPSHOTs and
local reactor builds provide development feedback; service releases pin qualified
immutable dependencies. Running tests, committing a feature or updating these
documents does not itself authorize publication or a service release.

For example, check a concurrency change locally with a fresh output directory:

```sh
make test-module MODULE=platform/platform-concurrency
make library-bundle LIBRARY_COMPONENT=concurrency LIBRARY_BUNDLE=.dev/concurrency-change
make library-consumer LIBRARY_COMPONENT=concurrency LIBRARY_BUNDLE=.dev/concurrency-change
```

Select the owning module/component for another library. The bundle command is a
packaging build, not the complete final quality gate. It records a dirty tree
when present; such a bundle can support local development checks but cannot be
signed as a release. Never reuse a bundle directory for changed bytes. For the
events adapter, pass the same-version core bundle as documented in publication.

## Release and upgrade

Publish the required library version, qualify it separately from Central and
GitHub Packages, then pin that version in the consuming service and verify the
final integration before releasing the service. For events, qualify the core
first and give the adapter that exact released core and its retained evidence.
Keep the two component tags and bundles separate.

After activation, for example, a diagnostics API addition could be developed as
`0.4.0-SNAPSHOT`, published as `0.4.0`, and selected by an IOC service release with
a different version. These are illustrative versions, not current release
instructions. Other services may remain on an older supported diagnostics
version until they choose to upgrade.

A SNAPSHOT is a development coordinate, not an immutable release baseline.
Retain the exact artifact/source identity when reproducing a problem; compare
public behavior and packaged dependencies, not only the version string. A
security update still needs compatibility review and the applicable release
security gate. Local consumers and documentation checks do not close advisory
dispositions or prove support for another Java/framework baseline.

For compatible fixes, increment PATCH; for compatible API additions, MINOR.
During `0.x`, incompatible supported-contract changes increment MINOR and need
migration notes. From `1.0`, breaking changes increment MAJOR. Our `0.x` rules
are an explicit project commitment; the number alone does not establish API
stability. Raising the minimum Java/framework baseline, changing failure or
concurrency behavior, and changing emitted field types also need compatibility
review.

Every release, including an RC, is immutable. Correct a published release with
a new version and component tag. Recover an incomplete publication with the
original signed files and deployment identity. Maintain guides without forcing
a binary release for a prose-only correction. Normal consumer upgrades do not
automatically upgrade every other service.

## What to include in the change description

State the shared API reused or extended, the reason for any local alternative,
the compatibility impact and selected versions. Identify affected consumers,
tests and documentation, and distinguish local build/package checks from actual
repository qualification. Record remaining implementation or compatibility work
instead of reporting it as completed.

## Sources of truth and maintenance

- [Release policy](../../RELEASE-PROCESS.md#reusable-library-versions) and
  [ADR 0048](../../ADR/0048-independent-library-versions-and-reuse.md): release
  ownership, version rules and activation criteria.
- [Root POM](../../../pom.xml),
  [publication admission](../../../.github/workflows/library-publication.yml)
  and [packaging checks](../../../tools/ci/library-publication.py): actual
  implementation. They currently enforce the shared version.
- [Library catalog](README.md): supported tools and their ownership boundaries.
- [KNOWN-ISSUES](../../KNOWN-ISSUES.md): TOOL-6 compatibility automation and
  TOOL-9 independent-version implementation.

Update both language versions when ownership, compatibility, development or
publication rules change. Mark independent versions operational only when their
POM, admission, dependency and consumer acceptance evidence exists. Keep permanent
release facts in the component's release record and accepted decisions in ADRs.
