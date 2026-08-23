---
title: "DATA-IMPORT-01 — source and row contract"
version: "0.3.0"
status: "Approved architecture baseline"
document_type: "Data contract design"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — source and row contract

## 1. Purpose

This document turns discovery decisions I-02 through I-22 and I-29 through
I-37 into an implementable declarative contract. Names below are proposed
configuration vocabulary. They become public operator API only after design
approval, configuration tests and publication in the operator guide.

The configuration describes supported data; it does not embed SQL, Java class
names, regular expressions from untrusted files or arbitrary expressions.
Every provider, transform, predicate, match definition and policy is selected
from a startup-validated registry.

## 2. Catalog hierarchy

```text
dataframe-import
  sources[]
    source identity and transport reference
    trust/authority ceiling
    contract allowlist
    detection/claim settings
  contracts[]
    identity and explicit version
    recognition signature
    dialect and charset
    processing/routing modes
    row/error/duplicate/TTL/slot policies
    artifact mappings[]
      primary or related branch
      column mappings
      record-key and match-key references
      merge overrides
```

Source and contract are deliberately separate. Structural recognition answers
which declared shape a file has; source authority decides whether that contract
is allowed to mutate its configured artifacts and fields.

## 3. Illustrative configuration

```yaml
ioc:
  dataframe-import:
    enabled: false

    sources:
      - id: trusted-local-feed
        transport: local
        location: ./var/import/inbox
        contracts: [ address-blacklist-v1, ip-list-v1 ]
        authority: standard-feed

      - id: trusted-smb-feed
        transport: smb
        endpoint: upstream-a
        location: inbound/ioc-import
        contracts: [ address-blacklist-v1 ]
        authority: authoritative-feed

    authority-profiles:
      - id: standard-feed
        artifacts: [ address_blacklist, ip_list ]
        maximum-merge-policy: fill-missing
        allow-related-routing: false
      - id: authoritative-feed
        artifacts: [ address_blacklist, ip_list ]
        maximum-merge-policy: authoritative
        allow-related-routing: true

    contracts:
      - id: address-blacklist-v1
        version: 1
        charset: UTF-8
        dialect:
          delimiter: ";"
          quote: '"'
          record-separator: CRLF_OR_LF
          header-required: true

        recognition:
          required-columns: [ forbidden_url, forbidden_ip ]
          optional-columns: [ id, score, source, description ]
          ignored-columns: []
          aliases:
            Forbidden URL: forbidden_url
            Forbidden IP: forbidden_ip

        mode: as-is
        routing: target-only
        row-failure-policy: accept-valid
        duplicate-policy: coalesce
        renew-unchanged: true
        formula-policy: reject
        merge-default: fill-missing

        artifacts:
          - name: address_blacklist
            role: primary
            record-key: address-first-non-empty-row-v1
            match-keys: [ forbidden-url-v1, forbidden-ip-v1 ]
            columns:
              - { target: forbidden_url, source: forbidden_url }
              - { target: forbidden_ip,  source: forbidden_ip }
              - { target: score,         source: score }
              - { target: source,        source: source }
              - { target: description,   source: description }
```

This example is not a new default. Classpath import remains disabled and a real
source must opt into an allowlisted contract.

## 4. Contract compilation and fingerprint

At startup, the catalog compiler:

1. normalizes names using the documented case and Unicode rules;
2. rejects duplicate IDs, headers, aliases and canonical-name collisions;
3. resolves every registry reference;
4. verifies mapped columns against configured artifact schemas;
5. verifies record/match definitions against the artifact identity catalog;
6. checks source authority is at least as restrictive as every referenced
   contract;
7. checks related routing and requested-slot scope;
8. validates nullable/required/type constraints and policy combinations;
9. creates a deterministic canonical representation;
10. computes a SHA-256 fingerprint including limits and all behavior-affecting
    policies.

The compiled catalog is immutable for one application run. A delivery pins
`contractId`, explicit `version` and fingerprint when recognized. Retry never
silently switches contracts. A changed configuration applies to the same bytes
only through a new delivery/replay.

## 5. Strict CSV boundary

### 5.1 Encoding

- Charset is declared; UTF-8 is the export-compatible default.
- The decoder reports malformed and unmappable input.
- A leading UTF-8 BOM may be accepted only as an explicitly tested dialect
  behavior.
- Charset guessing and silent replacement are forbidden.

### 5.2 Dialect

Delimiter, quote, escape, record separator, comments, surrounding-space
behavior and header presence are declared. Parser limits include maximum
record bytes, column count, field bytes and row count. A dialect cannot enable
arbitrary multi-character parsing logic.

### 5.3 Headers

Header order is irrelevant. Processing follows:

1. decode raw header text;
2. apply declared normalization;
3. map declared aliases to canonical names;
4. reject duplicate canonical names;
5. require all required columns;
6. allow only declared optional/ignored columns;
7. reject every unexpected or ambiguous header.

A declared but absent optional mapped column yields `ABSENT` for every row.
An ignored column never reaches mapping or reporting.

## 6. Exact-one recognition

Recognition is deterministic filtering, not scoring:

1. select only contracts allowlisted by the source;
2. select declared charset/dialect candidates whose header can be parsed within
   limits;
3. apply required, optional, ignored and alias rules;
4. apply any declared safe structural predicates;
5. require the result count to be exactly one.

Zero matches produces `IMPORT.CONTRACT_NOT_RECOGNIZED`. More than one produces
`IMPORT.CONTRACT_AMBIGUOUS`. Both are critical delivery errors. Contract order,
filename and closest-match score never break a tie.

## 7. Cell state and value normalization

For each mapped target column:

| Source condition | Import state |
|---|---|
| source column is absent or mapping has no input | `ABSENT` |
| empty cell | `NULL` by default |
| exact configured null literal | `NULL` |
| any other successfully decoded text | `VALUE` |

Whitespace, case, refang, parsing and canonical normalization occur only when
declared by mapping or by `processed` ownership. A string such as `NULL` is not
magically null unless listed as a null literal. Null literals are exact after
the declared pre-normalization step.

`ABSENT` and `NULL` remain distinct through staging and duplicate coalescing.

## 8. Merge policies

The effective policy is resolved in this order:

```text
source default < artifact override < target-column override
```

The source authority profile sets a ceiling: a contract cannot escalate itself
to a more destructive policy.

| Policy | Incoming `ABSENT` | Incoming `NULL` | Incoming `VALUE` |
|---|---|---|---|
| `keep-existing` | keep | keep | keep when record exists |
| `fill-missing` | keep | keep | write only when existing is null |
| `replace-non-null` | keep | keep | replace when different |
| `authoritative` | keep | clear | replace when different |
| `reject-conflict` | keep | reject if existing non-null | reject if different non-null value exists |

For a new record, `ABSENT`/`NULL` remain null and `VALUE` populates the field,
subject to required-field validation. Stable identifying fields are evaluated
before ordinary field merge: a conflicting non-null stable value rejects the
logical row even under `authoritative`.

## 9. Duplicate rows

Duplicate grouping uses the compiled logical identity after declared
normalization and before live DB matching.

### 9.1 `coalesce` (default)

- merge `ABSENT` with the other state;
- merge equal `NULL`/`NULL` and normalized-equal `VALUE`/`VALUE`;
- a `NULL`/`VALUE` or unequal `VALUE`/`VALUE` conflict is evaluated by the
  contract's duplicate conflict rule and defaults to row-group rejection;
- requested slots must be absent or equal;
- source row numbers are retained in the safe report as a group.

Coalescing is commutative and deterministic. File row order cannot choose a
winner.

### 9.2 `keep-first`

The smallest physical record number is retained after strict parsing. Other
records are reported as duplicates and cannot partially contribute fields.
This policy is explicit because its result intentionally depends on source row
position.

Apply-in-order updates to live canonical state are forbidden for both modes.

## 10. Compound records and matching

One CSV record may map several IOC-bearing columns into one artifact row. Those
values jointly describe one malicious resource in that list; they are not
split into independent rows unless the contract explicitly defines related
artifact branches.

Each artifact definition names:

- a record-key definition for new row identity;
- allowed match-key definitions, evaluated from the incoming logical row;
- stable and mutable target columns.

All non-ABSENT usable match keys are looked up against active aliases at one
`asOf`. Candidate lifecycle IDs are unioned:

- empty union: create a new record;
- one lifecycle: validate stable fields, then merge;
- more than one lifecycle: reject `IMPORT.MULTIPLE_ACTIVE_MATCHES`.

One-to-many is represented by repeated scalar rows and a composite record key.
For example, `(URL A, IP B)` and `(URL A, IP C)` are distinct rows. A later
input containing only `URL A` matches both and is therefore ambiguous rather
than silently merging the records.

## 11. Routing and row atomicity

`target-only` creates only the primary artifact branch. `related-artifacts`
may create deterministic compatible branches declared in the contract and
permitted by the source authority ceiling.

The source row number is the logical atomicity boundary. If any primary or
related branch has a mapping, validation, identity, matching, merge or slot
error, every branch of that logical row is rejected. Valid logical rows may
continue under `accept-valid`; `reject-delivery` turns any row rejection into a
pre-commit delivery rejection.

No relationship is persisted between lists after promotion.

## 12. Processing modes

### 12.1 `as-is` (default)

- mapping supplies final public artifact fields;
- only explicitly declared transforms run;
- artifact types, lengths, formats, record identity and final schema are
  validated;
- derived values from the ordinary pipeline are not recomputed;
- differing mapped values are merged according to policy.

### 12.2 `processed`

- mapping identifies raw semantic input and operator-provided fields;
- refang, extraction, classification and artifact preparation use the existing
  framework-free registries/services;
- derived-field ownership belongs to current processing behavior;
- operator-provided non-derived fields still follow declared merge policy;
- results are emitted in bounded chunks into staging while preserving the
  primary compound-row correlation.

Switching mode does not change matching, active-only behavior, row atomicity,
delivery atomicity or lifecycle semantics.

## 13. Requested export slots

A contract may map a requested `id` only when it declares one profile and the
primary artifact participates in stable export slots. The column is not a
canonical identity and is removed before public business-field merge.

- no request: normal allocator;
- new lifecycle and free request: exact slot;
- new lifecycle and occupied request: smallest free positive fallback plus
  safe report code;
- duplicate group with different requests: reject the group;
- matched survivor with the same request: no-op;
- matched survivor with a different request: preserve existing plus report by
  default, or reject under `reject-mismatch`;
- automatic survivor renumbering is forbidden.

## 14. Formula safety

Text destined for spreadsheet-visible free-text columns is rejected by default
when its first non-whitespace character is `=`, `+`, `-` or `@` (with exact
characters finalized by the security registry). The service does not silently
prefix, escape or otherwise mutate `as-is` data.

A `machine-only-preserve` policy may permit exact bytes only for a separately
documented output trust boundary. It cannot be enabled above the source
authority ceiling and must not claim spreadsheet safety.

## 15. File- and row-level errors

Critical delivery errors include ownership/snapshot failure, invalid encoding,
unparseable structure, header violation, zero/ambiguous contract, hard resource
limit, sealed-stage mismatch and internal consistency failure. They produce no
canonical writes.

Row errors include invalid target value, duplicate conflict, formula-dangerous
text, multi-match, stable-identity conflict, merge conflict, fan-out branch
failure and requested-slot conflict. Their disposition follows
`accept-valid|reject-delivery`.

Every code is stable and generated into the diagnostics catalog. Reports carry
delivery ID, row numbers, branch/artifact and codes with bounded safe detail;
they never carry raw source values.
