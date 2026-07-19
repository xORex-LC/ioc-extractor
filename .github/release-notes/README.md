# Release notes sources

This directory contains the curated source text used for GitHub Releases.

One file is required for every release tag and is named after that tag, for
example `v0.1.1.md` or `v0.2.0-rc.1.md`. The tag workflow reads that exact file
from the tagged commit; it does not generate or infer public notes from commit
subjects.

The required content and review criteria are defined in
[`docs/RELEASE-PROCESS.md`](../../docs/RELEASE-PROCESS.md#release-notes-contract).
Creating the notes file does not publish a release. The workflow creates only a
draft, and publication remains an explicit maintainer action after asset and
checksum review.
