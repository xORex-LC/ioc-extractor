# Repository controls

## Purpose and boundary

These JSON requests describe the reviewed GitHub repository settings for branch
changes, release tags and remote Actions. They are editable inputs for the GitHub
REST API, not workflow jobs: committing a file does not activate its settings.
Read back the remote configuration before claiming enforcement.

| File | Responsibility |
|---|---|
| [branch-ruleset.json](branch-ruleset.json) | PRs, strict baseline CI and no force-push/delete on `main` and all `release-*` branches |
| [tag-ruleset.json](tag-ruleset.json) | Update/delete protection for application and admitted component tags |
| [actions-permissions.json](actions-permissions.json) | Full-SHA admission for remote Actions without changing the allowed publisher list |

## Ownership and behavior

One maintainer reviews and merges PRs after required CI. The branch ruleset needs
zero approving reviewers and has no bypass actors. Creating a new release branch
is allowed before its initial CI results; later changes require PRs and checks.
Merge commits remain permitted. The ruleset does not validate the source chosen
for a new branch or enforce a SemVer branch name.

Existing classic branch protection remains in place. In particular,
`release-0.3.0` additionally requires `pmd-source-policy` and `library-consumer`.
The baseline checks exist in the supported `main`/release CI; review this list
when the workflow changes. Integration ID `15368` identifies GitHub Actions.

The tag ruleset retains administrator bypass (repository role `5`). Its broad
patterns select protected tags; publication helpers validate version syntax and
source identity. Update the patterns when admitting another library. Existing
published tags and bytes remain immutable under the release policy, including
when the administrator could technically bypass the ruleset.

The Actions setting applies repository-wide, including workflows selected from
older refs. Inspect supported refs before enabling it. Reusable-workflow calls
have a separate admission contract; this setting does not establish their full
dependency integrity or independent trust of Maven artifacts.

## Applying and verifying

Use the ruleset API to create a new ruleset or update its existing ID, and the
Actions permissions API for the Actions request. Identify the existing ruleset
by name/target before creating one; do not accumulate duplicates. Compare the
read-back writable fields to these files, and preserve other repository settings.
The rules-for-a-branch API can inspect a future release name without creating it.

Publication approval stays separate: the library publishing environment and
manual promotion of an application draft retain their own authorization. These
requests neither publish nor deploy anything and do not qualify a release.

Project policy and status belong to [Security Engineering](../../docs/SECURITY-ENGINEERING.md)
and the [release process](../../docs/RELEASE-PROCESS.md). Workflow implementation
is under [workflows](../workflows); shared leaves are described in
[CI tools](../../tools/ci/README.md).
