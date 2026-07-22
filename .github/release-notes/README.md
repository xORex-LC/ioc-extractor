# Release notes sources

This directory contains the curated source text used for GitHub Releases.

Starting with `v0.2.0`, one file is required for every release tag and is named
after that tag, for example `vX.Y.Z.md` or `vX.Y.Z-rc.N.md`. The tag workflow
reads that exact file from the tagged commit; it does not generate or infer
public notes from commit subjects.

[`v0.1.0.md`](v0.1.0.md) is the only historical-import exception. Version
0.1.0 predates this repository-owned source contract, so the normalized archive
was added later and says so explicitly. It does not rewrite the immutable tag or
the already-published GitHub Release.

## Authoring

1. Copy [`TEMPLATE.md`](TEMPLATE.md) to the intended tag name.
2. Keep every required H2 section. Use an explicit `Not applicable` disposition
   when a section has no content.
3. Replace all template tokens and guidance comments.
4. Use `make release-notes-context PREVIOUS_TAG=vX.Y.Z TARGET_REF=HEAD` to
   collect the technical inventory. Add `GITHUB=1` to include merged PRs when
   GitHub CLI authentication is available.
5. Curate user and operator impact; do not paste the inventory into the notes.
6. Run the documentation/contract gates before the final version commit.

The structural test checks file names, title/version agreement, mandatory
section order and unfinished template markers. It cannot prove descriptions,
defaults, mitigations or compatibility claims are semantically correct; those
remain review responsibilities.

## Reference policy

Prefer references in this order:

1. pull request or issue for reviewed scope and discussion;
2. ADR for architectural rationale;
3. one full compare range for the complete technical history;
4. a raw commit only when no better durable reference exists and the exact
   implementation transition materially helps investigation.

The tag, embedded build metadata and artifact digest establish release identity.
Per-bullet commit links are not required and normally make public notes harder
to read.

GitHub-generated notes, local Git history and AI summaries are input material,
not publication authority. They may omit direct commits or misunderstand
operator impact. The committed file is reviewed by a maintainer before tagging.

The required content and review criteria are defined in
[`docs/RELEASE-PROCESS.md`](../../docs/RELEASE-PROCESS.md#release-notes-contract).
Creating the notes file does not publish a release. The workflow creates only a
draft, and publication remains an explicit maintainer action after asset and
checksum review.
