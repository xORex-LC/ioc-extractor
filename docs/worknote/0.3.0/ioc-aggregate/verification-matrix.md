---
title: "DATA-AGGREGATE-01 — Verification plan"
version: "0.3.0"
status: "Discovery in progress"
document_type: "Verification plan"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — verification matrix

All rows are planned, not executed feature tests. Conditional cases become
required when the corresponding discovery decision admits them.

| ID | Behavior / evidence | Dependency |
|---|---|---|
| V-01 | Exact five-column order, delimiter, quoted non-null fields, NULL token, encoding and absence of public ID | Final wire contract |
| V-02 | IPv4 and each supported hash type populate only the intended carrier; hash normalization | Mapping decision |
| V-03 | URL/domain and decorated-address routing follows the amended contract; unsupported values cannot become hash or all-NULL identity rows | I-02 |
| V-04 | Same four-field tuple plus changed name updates one active row; different tuple stays distinct | BR-04 / I-12 |
| V-05 | Equal NULL positions match; empty-string distinctions follow the accepted contract | I-12 |
| V-06 | Repeated same name; last nonempty label and corresponding occurrence within one document; all-unnamed IOC retained | I-05 / I-08 |
| V-07 | Out-of-order completion respects durable registration order; retry/restart preserve priority; same-document occurrence selection remains stable | I-05 |
| V-08 | Active renewal, deadline equality, expiry/reappearance and history remain coherent | I-10 |
| V-09 | Name-only mutation updates projection and agreed export revision/cadence; identical repeat follows no-op policy | I-11 |
| V-10 | Existing four artifacts retain their supported mapping and duplicate behavior | Always |
| V-11 | New schema admitted on fresh and existing databases; upgrade/rollback and empty-start verified; no backfill | I-06 / I-09 |
| V-12 | Managed import routing, source authority, processed/as-is differences and cross-path order | Required; exact import policies pending |
| V-13 | New mapping configuration validates strictly and changes relevant fingerprints; old syntax remains supported | Selected extension |
| V-15 | Five observed marker forms plus existing long FSTEK markers; overlapping/embedded matches, punctuation, NBSP, nearest preceding attribution and shared-artifact impact | BR-11 |
| V-16 | ABSENT/NULL/empty/whitespace-only name preserves existing name; unnamed insertion stores NULL; no implicit clear | BR-12 |
| V-14 | Focused tests, full verify, PMD and applicable analyzer review; published docs and consumer fixture | Implementation completion |

Use synthetic independently specified fixtures. The local sample is not a
versioned golden corpus and contains malformed IOC values. Concurrent tests
require bounded coordination, JUnit timeout and worker cleanup under repository
policy. Benchmark scope should follow measured added work, not copy TTL/import
load sizes without justification.

## Technical-design qualification additions

These cases qualify proposed mechanisms, not additional accepted product behavior.
Resolve the referenced product choices before asserting a final expected outcome.

| ID | Scenario and required evidence | Slice |
|---|---|---|
| V-17 | Independent document/import registrations share one durable order; duplicate registration returns the same order; overflow and namespace mismatch fail before mutation | P2 |
| V-18 | Crash after reservation, registration, claim rename and ledger link: recovery adopts the same occurrence/file/order or rejects ambiguity | P2 |
| V-19 | Same-size/same-mtime file replacement, missing file identity and open-writer mutation cannot silently inherit old priority | P0/P2 |
| V-20 | Commit interruption between public value, provenance and receipt rolls everything back; crash after commit only finalizes coordination | P4 |
| V-21 | Newer same-name observation advances provenance without public revision; intermediate older different-name completion cannot overwrite it | P4 |
| V-22 | Newer empty-name observation does not suppress an older eligible nonempty value; same rank/position with conflicting value is rejected | P4 |
| V-23 | Identical-byte redelivery reuses prepared incoming name/position with a new rank; actual retry retains rank and does not repeat lifecycle confirmation | P3/P4 |
| V-24 | Processed-import source.label binding works for name and another configured column; ambiguous binding fails; authority restrictions remain effective | P5 |
| V-25 | Sealed stage recovery detects changed effective mapping/policy fingerprint; old receipts safely fall back to ETL without changing occurrence identity | P3/P5 |
| V-26 | Both disabled-lifecycle JDBC and fixed-lifecycle paths enforce field ordering; dry-run/help/validation do not register observations | P2/P4 |
| V-27 | Terminal handshake recovery covers success, rejection and quarantine; retention preserves pending references; missing resumed registration never receives a new rank | P2/P6 |
| V-28 | Crashed oneshot registration remains visible until safe explicit resolution; a live long invocation is never age-deleted | P6 |
| V-29 | Expiry/recreation, compatibility-mode cutover, legacy unranked work barrier and coordinated restore follow the documented contract | P6 |
| V-30 | Lost change hint still leads to projection/export through durable reconciliation; name-only delivery follows Q-01 | P5 |
| V-31 | Distinct raw indicators normalizing to one key select a whole occurrence; mixed artifact policies preserve existing outputs; classifier work is not multiplied by duplicates | P3 |
| V-32 | ArchUnit/module checks preserve inward dependencies; published concurrency API unchanged; representative duplicate-heavy memory and writer latency compared to baseline | P7 |

## Crash matrix checkpoints

Inject failures immediately before and after each durable operation, including
failure while recording its completion in the other database. Verify recovered
public rows, field origin, order counter, file ownership, receipts and terminal
state together. Do not infer crash safety from testing only the happy-path API.

Exercise older-first and newer-first canonical commit schedules with independent
connections and controlled barriers. Include document versus import contention,
not only two mock document workers. This qualifies ordering without enabling
multiple normal document workers in production. Keep locks/transactions short;
resource cancellation and database-busy outcomes must have bounded recovery.

V-02/V-03/V-12/V-13 additionally cover the [network amendment](network-routing-amendment.md):
structural column gates, clean domains, URL overlap suppression, configurable
carrier cardinality and processed/as-is validation. These checks are planned.

Accepted routing checkpoint: verify full URL and scheme-less host-plus-path
retain their observed representation in url_match, while bare domain and bare IP
never enter it. No automatic scheme insertion; no artifact-name dispatch in
generic mapping or storage. Scheme-less port-only endpoints are accepted in url_match without inserting a scheme.

CSV duplicate qualification: last accepted nonempty-name record wins by logical
record order for each normalized full key; later empty names preserve it; all-empty
groups retain one unnamed row. Include multiline quoted cells, invalid later
records, staged recovery and unchanged legacy contract behavior. Name-only public
changes must reach ordinary export/delivery; same-value origin changes must not
create a public revision. Q-07 lifecycle restart is accepted: delayed previously uncommitted input creates
a new lifecycle after expiry; replay of an already committed operation must not.
