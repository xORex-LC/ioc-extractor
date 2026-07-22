<!--
release-notes-template

Copy this file to vX.Y.Z.md or vX.Y.Z-rc.N.md. Replace every placeholder and
remove all guidance comments before review. Keep every H2 section; write an
explicit "Not applicable" disposition instead of silently deleting a section.
Public notes are curated for users and operators, not copied from Git history.
-->

# IOC Extractor X.Y.Z

<!-- One short paragraph: audience, outcome and why this release matters. -->

## Highlights

<!-- Three to seven user-visible outcomes, not implementation chronology. -->

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
State prerequisites, backup unit, data/config migration, operator actions and
rollback. Explicitly say when this is a fresh install or rollback is unsupported.
-->

1. <pre-upgrade or installation action>
2. <configuration/data migration action>

Rollback: <supported procedure, boundary or explicit unsupported disposition>.

## Breaking or observable contract changes

<!--
For every applicable surface state: what changed, who is affected, automatic
migration, operator action and rollback. Machine-readable changes also require
minimal before/after payloads and parser/query examples.
-->

Not applicable. <!-- Replace when the release changes an observable contract. -->

## Known issues

<!-- Only operator/user-relevant issues; link the durable registry ID. -->

- <issue ID, impact, safe mitigation or explicit Not applicable disposition>

## Verification

<!--
Record the required gate classes and any deliberately skipped environment check.
Exact candidate commit/digest evidence lives in the release ledger/workflow and
must not be guessed before the final commit exists.
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
