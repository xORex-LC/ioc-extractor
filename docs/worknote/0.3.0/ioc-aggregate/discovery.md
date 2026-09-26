---
title: "DATA-AGGREGATE-01 — Discovery worknote"
version: "0.3.0"
status: "Discovery complete; implemented and qualified"
document_type: "Discovery worknote"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — discovery

## Confirmed requirements

| ID | Requirement | Status |
|---|---|---|
| BR-01 | Support an additional output dataframe represented by local `dataframe/IOC_aggregate.csv` | Confirmed |
| BR-02 | Use the existing processing/storage/export mechanisms where their contracts fit | Implemented; ordered-field seams added where required |
| BR-03 | Create a new artifact, rather than a union of existing tables | Confirmed |
| BR-04 | Match by the complete tuple `(ip_address, url_match, host_match, hash)`, excluding `name`; retain one row and replace `name` with the latest received value | Confirmed; registration-order priority accepted |
| BR-05 | url_match contains URL text; host_match contains only clean domain names | Confirmed 2026-09-22; supersedes temporary NULL rule |
| BR-06 | Include IPv4, hashes, URLs and domains; one observed carrier per row, no correlation; configurable routing/shape policy | Confirmed 2026-09-22 |
| BR-07 | Start maintaining a feature documentation bundle alongside TTL/import worknotes | Authorized 2026-09-21 |
| BR-08 | Start the new artifact empty; no population from existing tables | Confirmed |
| BR-09 | Support managed CSV import of the aggregate schema using existing import mechanisms | Implemented and qualified |
| BR-10 | Configure duplicate/update behavior per artifact; make the mechanism reusable for existing and future artifacts | Confirmed |
| BR-11 | Extend configured section-marker regexes to recognize the label forms present in the sample; these occur in production | Confirmed |
| BR-12 | Preserve the existing name when a repeat has no new source label | Confirmed |

The sample has five ordered columns and no public ID. Treat its layout as input
to the output contract, not its malformed values as required generated output.

## Interview and decision ledger

| ID | Topic | Disposition / next question |
|---|---|---|
| I-01 | Hash routing | IMPLEMENTED: reusable `when-types` gate selects eligible IOC types for one scalar cell; it does not collect multiple values. Existing `when-type` remains compatible |
| I-02 | Domain/URL representation | DECIDED: include URLs and clean domains in separate carriers; see [amendment](network-routing-amendment.md); full URLs and scheme-less host-plus-path accepted; bare domain/IP excluded from url_match |
| I-03 | `name` formatting | DECIDED: use existing regex attribution and `source.label`; extend section-markers for observed production labels. No conversion of long labels into abbreviated labels requested |
| I-04 | Identity and update | DECIDED: all four non-name fields determine equality; update `name` on a later matching observation |
| I-05 | Latest observation order | DECIDED: later durable service registration has priority; errors, retry and restart do not raise it. Within a document, select the last nonempty label and its corresponding occurrence in text order; preserve other artifacts' behavior |
| I-06 | Initial population | DECIDED: start empty; only subsequent observations populate the new artifact. Historical backfill is a future possibility outside this feature |
| I-07 | Managed import | IMPLEMENTED: explicit `ioc-aggregate-v1` target-only contract; no automatic synchronization from ip_list/hashes. Recognition, mapping, authority and processed/as-is policies are qualified |
| I-08 | Missing label | DECIDED: configure empty-value handling per field. For aggregate name, ABSENT, NULL, empty and whitespace-only input preserve the existing value; new rows without a name store NULL. No explicit name clearing in this version |
| I-09 | Publication | IMPLEMENTED: generated `IOC_aggregate_generated.csv`, isolated `ioc-aggregate` immutable export profile, enabled sink and operator-disabled managed import |
| I-10 | Lifecycle | QUALIFIED: existing lifecycle behavior, renewal/update, expiry/reappearance and empty initial population are preserved |
| I-11 | Export on name change | DECIDED: a changed public `name` advances content revision/projection work and becomes eligible for normal export cadence; identical name does not imply public mutation |
| I-12 | Equality details | IMPLEMENTED: normalized full four-carrier tuple is record/match identity; NULL is canonical absence and empty carrier text is rejected. Future multi-carrier correlation requires a new contract |

## Clarifying examples

A first row `Source A;192.0.2.1;NULL;NULL;NULL` followed by the same tuple from
Source B must leave `Source B;192.0.2.1;NULL;NULL;NULL` as the single active row.
This is an update requirement, not inclusion of `name` in the identity key.

Creating the new table does not automatically copy old records into it. Managed
import writes according to its own routing contract; adding an output artifact
alone does not make it a live view of other tables.

No decision was inferred from unanswered questions. The later decision entries,
technical design and accepted ADR resolve publication, lifecycle/export and
input contracts without changing the earlier interview history.

## Follow-up clarification — 2026-09-21

The user moved the documentation changes to `feature/dataframe/new-artifact`.
The initial all-NULL example concerned the earlier request to include domains;
it is not an expected IP/hash row. Excluding domains/URLs resolves that scope
conflict. Artifact-level accepts and column gates should express this routing.

Document extraction distributes each IOC to all configured artifacts whose
acceptance/filter rules match. Managed import recognizes exactly one explicit
versioned source contract, not an automatically inferred sink schema. The
contract selects target mapping and routing within source authority. Target-only
is supported; related-artifact routing also exists and is not newly requested.
SQLite tables hold canonical artifact data; generated CSV files project it.

## Confirmed marker coverage and missing-label rule — 2026-09-21

The user confirmed the multi-type scalar gate, initial IP/hash-only scope,
per-artifact update policy, empty start and managed import interpretation.
Missing attribution on a repeated observation must preserve the existing name.

The sample contains 219 distinct labels across these five observed forms:

| Form | Distinct labels | Example |
|---|---:|---|
| BIB number | 119 | `БИБ-101211` |
| Request number | 1 | `Заявка_6619` |
| FSTEK date and reference, underscore-separated | 97 | `ФСТЭК_02.04.2026_240/93/2124` |
| FSTEK reference without date | 1 | `ФСТЭК_240/93/7415` |
| FSTEK date and reference, space/number-sign form | 1 | `ФСТЭК 21.05.2025 №240/93/1329` |

Preserve the existing long `Письмо ФСТЭК России ... № ...` form as well.
These are shape examples, not a whitelist of source numbers or dates. The owner
confirms production occurrence; no deployed production inspection was performed.
Recognition is not a request to rewrite source labels into one uniform format.
Exact RE2-compatible expressions, token boundaries and overlapping-match rules
will be qualified with synthetic fixtures before changing runtime configuration.

## Accepted ordering behavior and resolved engineering design

The owner proposed using metadata to identify which source entered processing
first. The owner accepted durable service registration as the priority boundary,
with unchanged priority through errors, retry and restart. The implementation
persists a monotonic observation admission sequence before dispatch;
keep it unchanged through retry/recovery and compare it transactionally when
applying configured latest-name updates. A later independently delivered
occurrence receives a new sequence even when its bytes are identical.

Define admission as service registration, not unknowable physical arrival time
on a local/remote filesystem. Existing import sequence orders import claims only;
a comparable document/import order requires a shared authority or explicit
precedence. Existing document `detectedAt` is supplied from the current clock on
each asynchronous ingest attempt and cannot be reused unchanged as first-arrival
truth. Observation UUID identifies an occurrence but does not order it.

The canonical field's accepted source order must survive restart and participate
in the name-update transaction. Same-value newer confirmations advance that
metadata without a public revision. Missing values follow the agreed
preserve-name policy and do not advance the stored nonempty field origin.
Keep lifecycle confirmation time separate from source ordering, and define
same-document duplicates and expired/recreated rows independently.

## Decision checkpoint — duplicate selection and empty values

The owner accepted the following observable behavior:

- Within one document, keep the last nonempty source label and the corresponding
  IOC occurrence, rather than splicing a later label onto an earlier occurrence.
  The ordering axis is text position, not marker number or date.
- Empty-name behavior is an artifact/field policy. Aggregate preserves existing
  names for absent, NULL, empty or whitespace-only input and stores NULL on an
  unnamed new row; other import contracts retain their own clear semantics.
- Later durable registration wins across deliveries. Processing completion,
  retries and restart cannot change the original priority.

A new all-unnamed IOC must still be retained. At this decision checkpoint, tie
treatment, atomic registration, cross-path order, legacy-work recovery and
schema/rollback remained engineering work rather than operator decisions. The
later technical design, ADR-0030 and implementation evidence record their
resolution.


## Technical-design checkpoint — 2026-09-22

The owner authorized the technical design and required explicit SRP/OCP/SOLID,
DDD and hexagonal boundaries, reuse of suitable libraries, and preservation of
the published concurrency component. See [technical design](technical-design.md)
and [dependency assessment](library-options.md). Engineering proposals there
resolve earlier alternatives provisionally; they are not new owner acceptances.
In particular, empty input not advancing field origin is the proposed interpretation
of preserving the newest eligible nonempty value. Product questions Q-01..07 are
collected in one table; unanswered questions remain unanswered.

Earlier dated IP/hash-only discussion below is historical and superseded by the
[network carrier amendment](network-routing-amendment.md).


## Accepted follow-up — export, CSV duplicates and port-only endpoints

The owner accepted ordinary export/delivery eligibility for name-only public
changes, last-nonempty-name row selection for repeated full keys within aggregate
CSV imports, and scheme-less host:port values in url_match. Keep these policies
configurable and preserve existing artifact/import contracts. Q-07 follow-up: the owner accepts recreating the IOC with a fresh lifecycle
when an older previously uncommitted delivery first succeeds after expiry.
Registration priority does not change; committed-operation replay remains
idempotent. Source-age restrictions are not introduced by this decision.
