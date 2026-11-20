# Activate IOC processing routes

[Русская версия](ru/ioc-processing-routes.md)

Every document uses a named processing plan; every `processed` import contract
must explicitly bind one. The shipped `original-document` routes full original
IOC values and preserves source observation accounting. Artifact mapping,
canonical record keys and write policies remain configured separately. A new
route changes future processing; it does not rewrite existing rows.

## Choose the output contract first

Choose which fields need a bare host and which retain a full refanged address.
For this guide's `customer-hosts` plan, the same document observations produce:

| Input observation | `masks.mask` | `address_blacklist.forbidden_url` |
|---|---|---|
| `hxxps[:]//best-malware[.]com/troyan.exe` | `best-malware.com` | `https://best-malware.com/troyan.exe` |
| `https://best-malware.com/other.exe` | `best-malware.com` | `https://best-malware.com/other.exe` |

The two host-derived mask candidates collapse to one final mask key, while the
blacklist retains both addresses. With the shipped mapping,
`10.93.12.187:9090/path` gives `ip_list.ip = 10.93.12.187` and the original-view
blacklist retains the full address in `forbidden_url`. A bare IP is excluded
from `masks`. Hashes remain in their own branch and mapped hash fields use the
configured uppercase transform.

`network.host` derives a host/IP; it does not modify `original`.
`lower-host` is a separate mapping transform: it lowercases scheme and host
while preserving path/query/fragment case, and does not remove a port or path.
Classification of each selected view still follows `ioc.classify.rules`.
Inspect the actual configured columns and identities before choosing a view.

## Build a complete candidate

Start from the installation's current complete YAML. Keep existing artifacts,
identity definitions, credentials, import contracts and every named plan still
referenced by a document or import contract. `ioc.processing.plans` is a list:
Boot replaces it as a whole rather than appending or merging plan names.

The following block shows the new plan and its document selector. Append the
plan to the complete candidate's existing plan list, and update the selector;
do not replace the list with this excerpt when existing contracts still need
other plans. The production template, for example, also binds its declared
aggregate import contract to `original-aggregate-import`, even with intake disabled.
Every enabled document artifact is covered below; adapt the list if your
artifact catalog differs.

```yaml
ioc:
  processing:
    document-plan: customer-hosts
    plans:
      - name: customer-hosts
        observation-selection: final-key
        views:
          - { name: host, operation: network.host, input: original }
        classifications:
          - { view: original, policy: configured }
          - { view: host, policy: configured }
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
            - id: aggregate-original
              artifact: ioc_aggregate
              default-view: original
```

`observation-selection: final-key` makes host-derived candidates compete by
final mapped artifact identity. It is also the default for a newly declared
plan; spelling it out makes this example's collapse deliberate. The shipped
original-value plan uses `retained-observations`, preserving original occurrence
and ID/provenance accounting. Neither choice replaces durable cross-run dedup.

After a branch is selected, the artifact's `accepts` and include/exclude filters
still inspect its default view. Each column then uses its own selected view for
type/condition gates, its provider and ordered transforms. A column listed in
`field-views` can read `original` even when the branch defaults to `host`.
A `const` provider stays constant; source/ID providers cannot be rebound. A disabled
artifact cannot be routed, and an enabled document artifact must be routed or
listed in `omitted-artifacts`, never both. An omitted artifact is not a request
to delete its existing rows.

## Route selection and unavailable inputs

| Routing mode | Selection behavior |
|---|---|
| `first` | Test branches in order and choose the first match. A reached blocked condition stops selection before a later branch can be used. |
| `all` | Select all matching branches in order; report blocked branches as failures alongside any selected branches. Actual commits still follow application failure policy. |
| `exclusive` | Require one matching branch; a second match rejects the selection. A reached blocked condition also prevents successful selection. |

A condition is one leaf (`on`, registered `predicate`, applicable `arguments`) or
one `all`, `any` or `not` group. Groups short-circuit in declared order. Quote
`'on'` in YAML as in the example, so tools using YAML 1.1 do not interpret the key
as a Boolean. A missing eligibility condition is unconditional.

The `on-unmatched` policy applies to an ordinary no-match: `skip` emits no
candidate, `reject` produces a routing error, and `route` selects the explicitly
named `default-branch`. That default branch has no eligibility condition.
An unavailable required view is not ordinary false and does not become true
under `not`; the no-match default is not automatic failure recovery. Even after
selection, unavailable required field views can block a destination. Other
unblocked destinations may prepare candidates, subject to the checkpoint.

Invalid authorities and unsupported address forms yield typed unavailable
views. Explicit `view.recover` creates a separate named view: its `input` names
the attempted view, `arguments.on-reasons` allows registered input-dependent
reasons, and `arguments.use-view` names the alternate. Recover only where keeping
that alternate is acceptable to the output contract. Successfully consumed
recovery produces a warning when every reached consumer of that failure is
recovered; any unrecovered consumer keeps the failure at ERROR. Changing a
failure policy does not invent a fallback.

Registry admission bounds plan/view/branch/condition complexity and rejects
unknown operations, references and illegal bindings. Configuration cannot inject
Camel endpoint URIs, scripts or an arbitrary expression language. See the
[configuration reference](configuration.md#ioc-processing-plans) for supported keys.

## Bind a processed import contract

For a contract mapping CSV column `ioc` to `masks.mask`, add this separate plan
to the same complete plan list. It has one authorized artifact branch; the
five-branch document example cannot be reused unchanged for a masks-only contract.
The plan is a list element, not a complete application configuration:

```yaml
name: import-hosts
views:
  - { name: host, operation: network.host, input: original }
classifications:
  - { view: original, policy: configured }
  - { view: host, policy: configured }
routing:
  mode: all
  on-unmatched: { action: reject }
  branches:
    - id: imported-mask
      artifact: masks
      default-view: host
```

The matching fragment inside that contract is:

```yaml
processed-route:
  plan: import-hosts
  inputs:
    - { artifact: masks, target: mask }
  outputs:
    - { artifact: masks, targets: [mask] }
```

Keep the contract's recognition, mapping, source authority, merge and row-failure
policies explicit. Every route input/output must name a mapped contract cell;
no route may replace source authority or create a second branch for the same
contract artifact. This fragment authorizes only the `mask` output: classification
codes computed while preparing the view are not automatically imported unless
the contract also maps and authorizes their output columns.

Each selected VALUE cell is refanged, stripped of surrounding whitespace and
must contain one whole IOC. Text around an IOC, multiple matches and malformed
network addresses are rejected. ABSENT, explicit NULL and VALUE keep distinct
meanings; a provider that contributes nothing is not an explicit CSV NULL.
Conflicting contributions from separate inputs reject the compound logical row.
`as-is` skips IOC routing but still applies its declared transforms/validators.
A `processed` contract without a route fails configuration admission even with
intake disabled.

To remove only HTTP(S) in one import scenario, declare
`{ name: scheme-free, operation: network.without-scheme, input: original }`
in its own plan, classify `scheme-free`, and select it for authorized fields or
the branch. `https://example.com:8443/Path?q=One#Part` becomes
`example.com:8443/Path?q=One#Part`; bare input is accepted unchanged. The suffix is
preserved by this operation, then normal artifact transforms still apply.
Unsupported schemes and malformed addresses remain typed failures. Only plans
selecting the view change; shared `ioc.sink.artifacts[].columns[].transform`
changes instead affect all processed paths using that artifact.

Use [`ioc import validate`](dataframe-import.md#validate-without-importing) with
`--source` and `--file` for a representative CSV before submission. Preview
requires the configured import source allowlist and enabled preview graph;
it does not prove what a future commit will do against changed canonical state.

## Activate and recover

1. Pause producers and drain unfinished document admissions/claimed files and
   unsealed pinned import deliveries under the old policy. Do not move owned
   processing files back into intake by hand. The document gate uses the complete
   processing fingerprint, so even changing another declared plan can require
   a drain. An unsealed import cannot be restaged with a changed pinned contract.
2. After draining, stop the service and take a consistent backup of both SQLite
   databases and owned files with the matching configuration/binary. Follow the
   [deployment procedure](deployment.md); copying two live DB files and their
   sidecars independently is not a consistent recovery point.
3. Prepare the complete candidate as above. Run installed `ioc-config check` and
   `ioc-config apply` through `sudo` using the
   [configuration workflow](configuration.md#how-configuration-is-applied).
   Successful preflight does not validate stored policy or recovery state.
   YAML restoration after failed activation does not undo database changes.
4. Use `ioc extract --source ... --dry-run` for source/preparation diagnostics,
   and import preview for a representative processed CSV. Dry run does not
   reserve IDs, commit canonical rows or write projections; it still reads and
   prepares data and is not a promise of zero runtime I/O. Preview/dry run do not
   qualify later persistence or delivery.
5. Submit a small controlled input and compare every affected artifact's values,
   source labels, classification codes, identity counts and generated CSVs.
   Check `ioc health` and diagnostics. `COMPLETED_WITH_ERRORS` returns extract
   exit `3`; `collect-and-continue` may already have committed valid rows.
   Warnings alone do not make extraction fail. Technical routing trace is
   value-free; opt-in per-item TRACE can contain IOC values and source details.

Existing rows keep their old keys. With fixed validity they expire through their
existing TTL; disabled lifecycle provides no expiry. Document commits remain
per artifact, so a later failure can leave earlier artifacts committed. Managed
import promotes accepted mutations in one cross-artifact dataframe transaction
and finalizes from the canonical receipt after a post-commit crash. Neither
workflow automatically backfills stored rows or supplies an old-plan executor.
A subsequent plan change needs the same drain/check/recovery discipline.

## Related documents

- [Configuration reference](configuration.md#ioc-processing-plans): supported keys.
- [Processing mechanics](../dev/processing.md): ownership and invariants.
- [CSV byte contract](../dev/csv-output-format.md): quoting, nulls, encoding and publication.
- [Managed import](dataframe-import.md): delivery operations and recovery.
