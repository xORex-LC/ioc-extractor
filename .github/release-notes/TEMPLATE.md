<!--
release-notes-template

Application notes only: copy to vX.Y.Z.md or vX.Y.Z-rc.N.md. Library publication
records use their component-scoped contract instead of this application title.
Replace every placeholder and
remove all guidance comments before review. Keep every H2 section; write an
explicit "Not applicable" disposition instead of silently deleting a section.
Public notes are curated for users and operators, not copied from Git history.
-->

# IOC Extractor X.Y.Z

<!-- One short paragraph: audience, outcome and why this release matters. -->

## Highlights

<!-- A small set of user-visible outcomes, scaled to release impact. -->

- <release highlight>

## Added

<!-- New supported capabilities, commands, configuration or artifacts. -->

- <added capability or explicit Not applicable disposition>

## Changed

<!-- Existing supported behavior that changed. -->

- <changed behavior or explicit Not applicable disposition>

## Fixed

<!-- User-visible correctness, reliability or security fixes. -->

- <fixed behavior or explicit Not applicable disposition>

## Upgrade notes

<!--
State supported previous versions/layouts, prerequisites and the backup/recovery
unit: application, operator configuration, unit state and consistent durable data.
Separate automatic data migration from manual configuration/actions. Include
activation and representative post-upgrade checks. State destructive or one-way
steps and whether rollback needs a coordinated restore or is unsupported.
Describe fresh/side-by-side installation explicitly when in-place upgrade is unsupported.
-->

1. <pre-upgrade or installation action>
2. <configuration/data migration action>

Rollback: <supported procedure, boundary or explicit unsupported disposition>.

## Breaking or observable contract changes

<!--
For every applicable surface state: previous/new behavior, affected consumers,
automatic migration, required action and rollback. Machine-readable changes also
need minimal before/after payloads and parser/query examples where relevant.
Consider CLI/exit codes, configuration/defaults, storage/identity, artifact/wire
formats, logging/health and installation interfaces. Use Not applicable only
after reviewing the supported surfaces; a compatible fix may still affect operation.
-->

- <changed behavior or explicit Not applicable disposition>

## Known issues

<!-- Only issues relevant to this release's users; link ID, impact and mitigation. -->

- <issue ID, impact, safe mitigation or explicit Not applicable disposition>

## Verification

<!--
Record required source gates and exact-bundle/target checks with their scope,
result and evidence references. Separate passed, failed, unavailable and explicitly
deferred checks; an offline skip is not a live pass. Exact final commit, bundle
digest and run identities live in produced evidence and must not be guessed
before the final candidate exists. Do not reuse evidence for different bytes.
-->

- <reactor, security, packaging and stand verification summary>

## Artifacts and checksums

<!-- Asset names must agree with the effective Maven version and workflow. -->

- `ioc-extractor-X.Y.Z.jar`
- `ioc-extractor-X.Y.Z.jar.sha256`

```bash
sha256sum --check ioc-extractor-X.Y.Z.jar.sha256
```

## Technical references

<!--
Optional. Prefer PR/issue and ADR links. Add one compare range for the complete
technical history. Link a raw commit only when no better durable reference exists.
-->

- Full change set: replace with the GitHub compare URL.
- Source tag: replace with the GitHub tag URL.
