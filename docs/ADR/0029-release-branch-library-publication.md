# ADR 0029: Release-branch publication with component tags

Status: Accepted, 2026-09-08.

## Context

ADR 0028 initially required the concurrency library to use the product's
`vX.Y.Z` tag selected from the default branch. The default branch remains on
the latest completed product release while the next release is assembled on a
`release-X.Y.Z` branch. Moving unfinished application code to the default
branch solely to publish the library would break that release model. A product
tag would also trigger the existing application release workflow.

GitHub discovers and manually dispatches a `workflow_dispatch` workflow only
when its workflow file exists on the default branch. Once registered there, a
maintainer can select another branch for the run. Therefore the discovery copy
on the default branch and the source commit admitted for publication are
separate concerns.

The owner also considered independent library versions. There is no current
consumer or release cadence that requires that operational cost.

## Decision

This ADR supersedes only ADR 0028's default-branch and product-tag selection.
The concurrency library keeps the product version line for now. A product
release `X.Y.Z` and its concurrency artifact use version `X.Y.Z`; an RC uses
the matching `X.Y.Z-rc.N`. A future ADR may introduce an independent library
version when real consumers need releases outside the product cadence.

Library publication uses an annotated component tag:
`ioc-platform-concurrency-vX.Y.Z` or
`ioc-platform-concurrency-vX.Y.Z-rc.N`. It does not match the application's
`v*` release trigger. The workflow is dispatched against the corresponding
`release-X.Y.Z` branch. Admission requires all of the following:

- the selected ref is exactly a `release-X.Y.Z` branch;
- the component tag is annotated and its version matches that branch;
- the tag resolves to a commit contained in the selected release branch;
- builds, signing and publication operate on that resolved commit;
- recovery uses the same tag, release branch, signed bundle and Central
  deployment identity.

The publication workflow file also exists on the default branch so GitHub can
register `workflow_dispatch`. Selecting the default branch or a development
branch fails admission before signing or repository access. This operational
workflow addition does not merge unfinished application code into the default
branch.

## Consequences

The library can be staged and published while the product release remains on
its release branch. Component publication and application release are
independently triggered, while their version numbers remain aligned. A
maintainer must create and preserve an annotated component tag and explicitly
select its matching release branch.

The default branch carries publication automation that may be newer than its
application code. The immutable component tag remains the source of build and
privileged publication scripts after admission. Repository publication and
independent consumption requirements from ADR 0028 remain unchanged.
