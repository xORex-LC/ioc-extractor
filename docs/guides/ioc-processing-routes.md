# Activate IOC processing routes

Named processing plans select views and artifacts for new document observations
and for explicitly bound `processed` import contracts. The canonical SQLite
record key and write policy remain configured per artifact. Enabling a route
does not rewrite existing rows.

## Choose the output contract first

Decide which artifact fields should contain a bare host and which should retain
the complete refanged address. For example, these two document values can map
to one `masks.mask` value while `address_blacklist.forbidden_url` retains both:

| Input observation | `masks.mask` | `address_blacklist.forbidden_url` |
|---|---|---|
| `hxxps[:]//best-malware[.]com/troyan.exe` | `best-malware.com` | `https://best-malware.com/troyan.exe` |
| `https://best-malware.com/other.exe` | `best-malware.com` | `https://best-malware.com/other.exe` |

The `network.host` view also strips a supported IP address's port and path.
With the shipped artifact mapping, `10.93.12.187:9090/path` produces
`ip_list.ip = 10.93.12.187`; the original-view blacklist branch stores the
full address in `forbidden_url`. Select `host` for that branch if the blacklist
must contain only bare IPs, and check its existing column provider and identity.
Unsupported schemes or invalid authorities yield a typed unavailable view.
Choose an explicit `view.recover` policy only when keeping the original value
is acceptable; the resulting accepted warning is visible in diagnostics.

The following excerpt is layered on the shipped artifact catalog. It covers
every enabled artifact; review it against your effective `ioc.sink.artifacts`
before applying it. Classification on `host` still uses the operator's existing
`ioc.classify.rules` rather than a hard-coded match code.

```yaml
ioc:
  processing:
    document-plan: customer-hosts
    plans:
      - name: customer-hosts
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

The artifact's `accepts`, filters, column providers, identity and write policy
still apply after a branch is selected. In particular, a bare IP is excluded
from the shipped `masks` artifact and admitted by `ip_list`. To retain the
original address in one field of a cleaned branch, set that column's
`field-views` entry to `original`; its provider must actually read an IOC value.
A `const` provider remains constant, and source/ID providers cannot be rebound.
Never assume that selecting a plan adds to legacy dispatch: it replaces dispatch
for the selected document scenario. A disabled artifact cannot be routed; an
enabled artifact must be routed or explicitly listed in `omitted-artifacts`.

## Bind a processed import contract

An existing `mode: processed` contract can select a named plan with an explicit
source-cell list and output allowlist. For a contract whose `masks.mask` column
maps from the CSV `ioc` column, a fragment inside that contract is:

```yaml
processed-route:
  plan: import-hosts
  inputs:
    - { artifact: masks, target: mask }
  outputs:
    - { artifact: masks, targets: [mask] }
```

`import-hosts` denotes a separate named plan with a single authorized `masks`
branch; the five-branch document example cannot be reused unchanged for a
one-artifact import contract. Adapt the plan's branches and the contract's
declared columns together: every
bound output must be an authorized contract target, and no route may replace
source authority or create a second branch for the same contract artifact.
Structured input cells must contain one whole IOC. Missing, explicit null and
value cells retain their distinct meanings. Two inputs that produce conflicting
identity-bearing values reject the one logical row. `as-is` contracts never run
IOC routing; unselected `processed` contracts retain their compatible path.
Use [`ioc import validate`](dataframe-import.md#validate-without-importing) on
the exact candidate before submitting a delivery.

## Activate and recover

1. Save the current configuration and both SQLite databases with their WAL
   sidecars. Keep an executable rollback binary and its compatible DB snapshot.
2. Drain unfinished document ingestion and unsealed pinned import deliveries
   under the old policy. A selected document policy change is blocked at daemon
   startup while unfinished intake exists; an unsealed pinned import delivery
   cannot be restaged under a different contract.
3. Stage a complete YAML candidate, including each list element you override.
   Run `<prefix>/bin/ioc-config check ./application.candidate.yml`, then
   `<prefix>/bin/ioc-config apply ./application.candidate.yml` as described in
   the [configuration guide](configuration.md#how-configuration-is-applied).
4. Submit a small representative document and use `ioc import validate` for a
   representative processed import. Compare canonical rows and generated CSVs
   for every affected artifact, including values, classification codes and
   identity counts. Check `ioc health`, diagnostic codes and bounded routing
   trace events; per-item tracing is opt-in and can be high volume.

Existing rows keep their old keys. When fixed lifecycle validity is active,
they age out by their normal TTL; disabled lifecycle offers no expiry promise.
Document commits remain per artifact, while managed import promotes all of a
delivery's accepted changes in one dataframe transaction and recovers from its
receipt after a post-commit crash. Changing a plan again requires the same
drain-and-validate procedure. There is no automatic backfill or stored old-plan
executor.

The [configuration reference](configuration.md#ioc-processing-plans) lists all
supported keys and bounds. The [processing capability](../dev/processing.md)
describes implementation ownership and the [managed import guide](dataframe-import.md)
describes delivery operations.
