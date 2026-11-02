# Release notes and publication records

This directory holds two related sources with different identities:

| Source | Naming and authority |
|---|---|
| Application release notes | `<application-tag>.md`, for example `vX.Y.Z.md` or `vX.Y.Z-rc.N.md`; read from the selected source by the application release workflow |
| Library publication record | `<artifact>-<version>.md`; component-scoped source, immutable bundle and repository evidence referenced by the library catalog |

The [project release policy](../../docs/RELEASE-PROCESS.md) owns general
pre-release/release rules. These files describe individual releases and their
actual impact/evidence. A library record is not an application notes file and
does not have to use its title or section template.

## Author application notes

1. Copy [TEMPLATE.md](TEMPLATE.md) to the intended application tag name.
2. Keep every required H2 section in order. Use an explicit `Not applicable`
   disposition where no content applies; remove all template tokens and guidance
   comments.
3. Explain supported user/operator outcomes. For observable changes, name the
   affected installations/consumers, before/after behavior, migration actions and
   rollback boundary. Include the recovery unit and post-upgrade validation.
4. Use `make release-notes-context PREVIOUS_TAG=vX.Y.Z TARGET_REF=HEAD` for a
   read-only inventory. `GITHUB=1` adds merged PRs when GitHub CLI authentication
   is available. Curate the result rather than pasting it.
5. State what verification establishes and which checks were unavailable or
   received a reviewed disposition. Final commit/digest/run facts belong to
   produced evidence; do not invent them before the final candidate exists.
6. Run the documentation and release-notes contract checks before tagging, and
   review semantics separately.

The [structural test](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ReleaseNotesContractTest.java)
checks application file names, title/version agreement, required section order,
common unfinished markers and selected historical invariants. It does not prove
compatibility, defaults, migrations, mitigation safety, verification freshness
or completeness of prose. Review those claims against their owning sources.

The detailed required content is in
[RELEASE-PROCESS — Release notes contract](../../docs/RELEASE-PROCESS.md#release-notes-contract).

## Maintain library records

Follow the [publication guide](../../docs/guides/library-publication.md) and
[library catalog](../../docs/guides/libraries/README.md). A record identifies
the exact coordinate/version, source/tag, supported contract and dependencies,
compatibility impact, signed bundle/manifest, repository qualification and
remaining constraints.

Separate the original publication identity from later recovery or consumer runs.
Preserve original signed-file hashes, source/tag and repository evidence.
Append clearly identified follow-up evidence where needed; never replace a
failed or unavailable original observation with an unrelated later pass.
Existing paired translations must retain the same facts.

Product-only structural tests do not validate these component records. Their
package/repository evidence comes from the owning publication contracts and
protected workflow. Writing a record does not authorize publication.

## Reference policy

Prefer references in this order:

1. PR or issue for reviewed scope and discussion.
2. ADR for architectural rationale.
3. One full compare range for complete technical history.
4. A raw commit only when no better durable reference exists and the exact
   transition materially helps investigation.

Tag/source identity, packaged metadata and bundle digests provide traceability.
Per-bullet commit links are unnecessary. Generated notes and local inventories
are curation inputs, not publication authority; a maintainer reviews public
impact before tagging.

Creating application notes does not publish a release. The application workflow
creates only a draft; publication is an explicit maintainer action after asset,
checksum, notes and required-evidence review.

## Historical archives

[v0.1.0.md](v0.1.0.md) is the sole historical-import exception enforced by the
application notes contract. It predates repository-owned source notes; the
normalized archive was added later and is labelled accordingly. It does not
rewrite the immutable tag or already-published GitHub Release.

From `v0.2.0` onward, each application release's committed notes are read from
its tagged source. Preserve archive facts, including missing original evidence,
instead of retroactively implying that newer controls ran for an older release.
