# Configuration and Camel execution design

Refinement of syntax, fallback and logging: [routing resolution contract](routing-resolution-and-logging.md).
Its proposed C4 contract specifies the recovery and diagnostic rules summarized below.

Status: proposed, 2026-09-27; source baseline `f43037ee87ef`, branch
`module/platform/router`. Builds on [confirmed contracts](camel-task-design.md).
The following YAML is a design illustration, NOT supported configuration or a
paste-ready overlay. No binder, Camel runtime or semantic compiler was implemented.
Camel qualification targets 4.22.1; exact Boot compatibility remains unverified.

## Configuration ownership

Keep one owner for each policy. Existing sink.artifacts owns schema, providers,
ordered string transforms, type/field gates and write-policy. artifact-identity
owns record/match keys. classify.rules owns predicate-to-code rules. New processing
configuration owns named views, classification bindings and ordered branch
selection. It references these existing definitions instead of copying schemas,
keys or winner rules into a routing DSL.

Compare three forms:

| Form | Advantage | Cost / disposition |
|---|---|---|
| Add host transform to columns | Small syntax change | Cannot change earlier classification/type routing; insufficient |
| Expose Camel YAML directly | Broad EIP expressiveness | Leaks runtime, arbitrary endpoints and error controls into operator policy; conflicts with agreed registered conditions |
| Compile bounded processing descriptors | Explicit views/dependencies and reusable current policy | Requires a compiler and validation; recommended |

The compiler produces an immutable finite plan, not a second runtime interpreter.
Camel owns generated operation sequencing and dispatch. Our small selector owns
FIRST/ALL/EXCLUSIVE outcomes where preselection is required; it must not evaluate
a duplicate Camel condition tree. No loops, joins across observations, user code,
arbitrary endpoints or one-to-many branch expansion enter v1.

## Concrete example: host-based masks, original blacklist

The example reuses complete existing artifact definitions; omitted definitions
remain in their current configuration. Every key under processing below is new.
`on` is quoted to avoid YAML-tool differences around boolean-like keys.

```yaml
ioc:
  processing:
    document-plan: network-views
    plans:
      - name: network-views
        views:
          - name: host
            operation: network.host
            input: original
        classifications:
          - view: original
            policy: configured
          - view: host
            policy: configured
        routing:
          mode: all
          on-unmatched: { action: skip }
          branches:
            - id: masks-host
              artifact: masks
              default-view: host
              eligibility:
                'on': original
                predicate: type-in
                arguments: { types: [IPV4, DOMAIN, URL] }
              field-views: { description: original }
            - id: ip-host
              artifact: ip_list
              default-view: host
              eligibility:
                'on': original
                predicate: type-in
                arguments: { types: [IPV4, DOMAIN, URL] }
            - id: blacklist-original
              artifact: address_blacklist
              default-view: original
              eligibility:
                'on': original
                predicate: type-in
                arguments: { types: [IPV4, DOMAIN, URL] }
            - id: hashes-original
              artifact: hashes
              default-view: original
              eligibility:
                'on': original
                predicate: type-in
                arguments: { types: [MD5, SHA1, SHA256] }
```

`network.host`, `type-in` and the binding syntax are proposed registry additions,
not existing names. `configured` references the existing classify.rules set,
not another rules catalog. Adding multiple named classification policies is a
separate extension; the current requirement can change existing configured rules.
`original` means the attributed, refanged/normalized observation at the processing
boundary, not byte-for-byte document text. Preserve raw source evidence separately
where already carried; do not rename original to imply forensic raw bytes.

The description override demonstrates binding only: the shipped description
provider is const, so it still produces its configured constant/null. To output
the original address there, the operator also changes that existing column's
provider to value. Do not silently override providers in field-views or duplicate
columns in this plan. This is an intentional output change, not a default.

The hashes branch is essential: selecting a custom document plan is a complete
replacement for legacy artifact dispatch, not an additive pass. Enabled artifacts
absent from the selected plan require an explicit omission acknowledgement or a
startup violation. Never silently stop producing hashes/ioc_aggregate because a
network-only example was copied. This excerpt illustrates four artifacts; the
shipped enabled ioc_aggregate must also be bound or explicitly omitted in a full
configuration. Disabled targets are rejected rather than silently enabled.

For a named branch, precedence is field-views[column] then default-view. Source
metadata and ID providers are context-owned, not view-derived. Reject meaningless
view overrides for those providers; constants may ignore the view. value and
address providers use their resolved view; match providers use the classification
bound to that same view. This default prevents mixing a cleaned value and unrelated
match codes by accident. If a future scenario needs cross-view match binding,
require an explicit reviewed extension rather than an implicit fallback.

Existing accepts/include/exclude are evaluated once against default-view. Existing
column when-type/when-types/when gates evaluate against that column's resolved
view. Existing provider and transform behavior then follows. Eligibility is an
additional precondition on source facts, useful for avoiding irrelevant derivation;
it does not replace the artifact's existing gate. The compiler emits one combined
selection plan with identified early and late conditions, not two full filters.

Optional boolean example (same grammar at any admitted condition site):

```yaml
all:
  - { 'on': original, predicate: type-in, arguments: { types: [DOMAIN, URL] } }
  - any:
      - { 'on': original, predicate: has-path }
      - { 'on': original, predicate: has-port }
  - not: { 'on': original, predicate: has-query }
```

AND/OR/NOT nodes are mutually exclusive shapes. Nonempty groups and a single NOT
child are validated. Existing when lists retain AND semantics; exclusion retains
its current any-match semantics. Parameter schemas come from registered factories,
not arbitrary reflection/expression evaluation.

## How this example behaves

| Input | masks using host | ip_list using host | blacklist using original |
|---|---|---|---|
| https://best-malware.com/a | best-malware.com | filtered by type | forbidden_url retains URL |
| https://best-malware.com/b | same final mask key | filtered by type | second distinct URL key |
| 10.93.12.187:9090/path | filtered by existing is-bare-ip exclusion | 10.93.12.187 | detailed address in forbidden_url |
| https://10.93.12.187/path | filtered as bare IP after derivation | 10.93.12.187 | URL in forbidden_url |
| Bare IPv4 | filtered by masks exclusion | same bare IP | forbidden_ip |
| Hash | network eligibility false; host not evaluated | not selected | not selected; hashes branch handles it |

No DNS or network connection is made for these strings. Host normalization follows
the shared domain parser and existing column transforms. Match codes are outputs
of the configured rules over the selected view, not constants in this table.

The shipped masks record key is [mask], ip_list is [ip], and blacklist is
[forbidden_url, forbidden_ip]. Thus the first two rows collapse only in masks.
A non-key description retaining original does not prevent collapse; configured
candidate/write policy determines the surviving value. Two branches targeting
one artifact share that artifact's key space. Branch IDs are provenance, not an
implicit extra identity column. Existing canonical records are not backfilled.

## Compile-time plan and runtime order

1. Bind effective configuration through existing IocProperties and unknown-key
   preflight. Collect errors; do not throw from binding constructors.
2. Resolve plan/branch/artifact/field/view/predicate references and parameter
   schemas. Build a typed descriptor from existing catalogs.
3. Validate acyclic view dependencies, operation input/output types and v1
   cardinality. Reject unknown/duplicate names, unreachable required bindings,
   missing classification and illegal input/output validation placement.
4. Compile early eligibility, required view closure, late artifact conditions,
   field gates and mapping calls. Preserve configured branch order; operation
   dependencies, not a second numeric priority, determine prerequisites.
5. Bind canonical key/merge policy by artifact reference; validate coverage of
   enabled outputs, import authorization, count/depth/size limits and fallbacks.
6. Fingerprint the admitted descriptors and semantic operation versions using
   existing policy identity; generate bounded Camel routes once at startup.

Do not use a runtime recursive plan interpreter inside a Processor, which would
leave Camel merely wrapping our own execution engine. The adapter emits direct
calls/EIP nodes for the finite plan. Shared view operations may be guarded by
precompiled demand conditions. Evaluate one shared view at most once per input;
store its immutable outcome in invocation-local state. No cross-input value cache.

For ALL, evaluate necessary selection conditions before any output branch runs.
Prerequisite view computation is allowed during selection because it is pure.
Avoid running host derivation for hashes or for an entirely ineligible scenario.
After selection, run required mapping-only dependencies and each selected branch.
FIRST and EXCLUSIVE retain declared short-circuit semantics; do not evaluate later
branches merely to make an explain report exhaustive.

A selected branch is not necessarily a produced row: it can return filtered or
expected-failure. FIRST chooses the first eligible branch, not the first branch
whose mapping happens to succeed. Retrying selection after a mapping failure
would be a different policy. A routing default applies to genuine no-match, not
failed prerequisites. No implicit original fallback is introduced.

## Missing views and expected failures in conditions

A boolean predicate only runs on an available typed view. A failed prerequisite
produces a blocked evaluation outcome, not false. In particular NOT(blocked)
must not become true. Boolean groups short-circuit in declared order; a reached
blocked operand propagates blockage, while an operand never reached has no effect.
Unknown view references are startup errors, distinct from runtime failed views.

In ALL, blocked branches contribute failure evidence and independent eligible
branches may prepare, as agreed. In FIRST, a blocked earlier branch prevents
proving which branch is first; stop selection with an expected failure. In
EXCLUSIVE, any reached blocked candidate prevents proving uniqueness; dispatch
none. Two positive matches establish ambiguity immediately. Unexpected predicate
exceptions abort the invocation. These refinements are proposed mode-specific
semantics, not additional already-approved business requirements.

Fallback is separate from routing default. The proposed `view.recover` operation
creates a separately named view with an explicit alternate and allowlisted expected
failure reasons; it does not change the meaning of `host`. It retains causal
evidence and passes the same final gates/validation. Resolve demanded consumers
before emitting diagnostics: recommend WARN when all recover, ERROR when any
remains blocked. Never publish ERROR and subsequently downgrade it. See the
[resolution contract](routing-resolution-and-logging.md) for syntax, mixed-consumer
cases and import reporting changes. No generic catch-all fallback.

## Generated Camel execution, conceptually

```text
application entry (one observation / one logical import row)
  -> invocation-local typed context
  -> compiled eligibility and prerequisite operation nodes
  -> ordered selection + blocked-branch evidence
  -> zero recipients: explicit result (no call to recipientList)
     otherwise: recipientList(validated internal endpoint list)
       -> branch-local context -> view/classification dependencies
       -> artifact and field gates -> existing mapping operations
       -> Prepared | Filtered | ExpectedFailure
  -> aggregate branch values and local diagnostics
  -> return typed preparation result
application finalization -> configured keys/reduction -> checkpoint -> write
```

For the example, host-based gates require host before final selection; mapping-only
views are computed only for branches that need them. Reusing a classification
means reuse of an immutable decision for the same (view, policy), not classification
by original IOC string across unrelated observations.

Use [Direct](https://camel.apache.org/components/4.22.x/direct-component.html)
for local invocation with explicit synchronous behavior and missing-consumer
failure. [Recipient List](https://camel.apache.org/components/4.22.x/eips/recipientList-eip.html)
executes selected branches with explicit sequential ordering, stopOnException,
no invalid-endpoint ignoring, and a supplied result aggregation strategy. Expected
failures are result values and therefore do not trigger exception stopping;
unexpected failures propagate. Disable redelivery. Generated endpoint names come
from validated IDs, never IOC text. Do not create routes/producers per observation.
This is execution design, not compiled Java DSL evidence for version 4.22.1.

Collectors are invocation-local. Branch copies cannot mutate a sibling's context;
Camel's carrier copy is not a deep-copy guarantee. Return each branch's diagnostics
only, not inherited ancestors. The common owner emits shared-operation diagnostics
once. Bounded diagnostic summaries must retain rejection evidence even if detail
retention fills; verify against current BoundedNotification/FailurePolicy semantics
instead of evaluating only a truncated list and losing a rejecting condition.

No DB connection, transaction, commit or control event belongs in these routes.
Existing post-commit events still act as hints. Sequential branch execution does
not eliminate concurrent calls from different observations: pure components and
shared compiled plans must be thread-safe. Keep deterministic occurrence metadata;
route arrival order must never replace registered observation order.

## Import attachment and limits

A proposed processed contract plan reference binds to its authorized artifacts
and explicit source-cell inputs. The document-plan above is not automatically
applied globally to every import source. AS_IS remains unchanged. Reuse the same
view/mapping definitions through a validated contract-specific projection of the
plan; do not silently expand a contract's destination set. Require an explicit
binding for each required input; avoid guessing input columns from output provider
names. Full import YAML should be specified with DataframeImportCatalogDraft's
versioned input/output-validation extension, not invented as a runnable example.

A logical row with several IOC cells can create several named original inputs,
with host views derived from explicitly identified cells. There is no global
ambiguous original binding and no Cartesian expansion. Existing compound-conflict
rejection remains. Required branch filtering or failure maps to the contract's
row-admission rules; generic routing skip cannot silently drop a required primary
artifact or bypass requested-slot/source-authority validation.

One observation yields at most one candidate per branch; several branches targeting
the same import artifact cannot create multiple conflicting logical artifact rows.
The import plan compiler must either prove compatible single-row assembly or
reject that configuration in v1. Document fan-out permissiveness does not override
import cardinality. Input and final-output validation have separate purposes.

## Migration, tests and remaining decisions

Source-backed changes: extend IocProperties with typed plan descriptors and
ConfigRegistryCatalog with operation/parameter metadata; evolve semantic checks
and ProcessingPolicyFingerprint together. Preserve artifact columns/key/write
policy ownership. Move view-aware mapping/classification once into the qualified
processing boundary; replace CSV-specific processed-provider inference. Camel
adapter owns code generation, endpoint lifecycle and result adaptation. Keep
application ports, import finalization and canonical reduction inward.

Legacy configurations compile to the compatibility plan. Opting into a named
plan replaces legacy dispatch only for that selected scenario. Changing list
entries via overlays must obey the existing whole-element binding contract;
examples must not imply name-based list merging. A future config-explain output
should show resolved branch/view/classification bindings without sample IOC data.

Required fixtures include the five network cases above, unchanged hashes and
explicit aggregate coverage; masks collapse versus blacklist retention; original
field override with const and value providers; NOT on failed views; ALL independent
success versus FIRST blocked precedence; empty recipients; fallback evidence;
import missing/NULL cells and required primary branch; policy changes during
pinned delivery; no backfill and normal TTL; concurrent invocation isolation.

Qualification measures plan compilation/startup, per-record Exchange allocations,
retained views/candidates, bounded diagnostics and import workspace memory. Limits
apply to number of plans, branches, views, condition depth and fan-out. No new SQL
index/schema is justified by this configuration layer; evaluate any observed
storage cost separately. These checks are planned, not executed.

The [routing resolution contract](routing-resolution-and-logging.md) now supplies
concrete recommendations for binding syntax, blocked selection, fallback diagnostic
disposition, omission syntax and logging; they remain proposed until accepted.
The confirmed expressiveness, expected-failure independence and v1 cardinality
are preserved. Implementation should begin with a reviewed example plus public
contract fixtures, not a general workflow language.

## Draft decision C3

**Context:** views and branches need concrete operator bindings without duplicating
schemas or creating a second execution framework. **Proposed decision:** compile
bounded processing descriptors referencing current artifact/classification/key
policies into Camel routes; distinguish blocked dependency outcomes from boolean
no-match and keep import authorization/cardinality explicit. **Alternatives:**
direct Camel YAML, per-column string cleanup, a custom interpreted DAG.
**Consequences:** new typed configuration/compiler work and explicit startup
coverage checks; one mapping/policy implementation and one branch execution owner.
This is a proposed design decision, not an accepted published ADR.
