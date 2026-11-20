# Dataframe files

<a id="dataframe"></a>
<a id="назначение"></a>
<a id="структура"></a>
<a id="заметки"></a>

## Purpose and boundary

Local reference CSV files and generated projections. Canonical SQLite storage,
by default `var/db/ioc-dataframe.db`, owns business data, identity and provenance.
Runtime schemas come from configuration, not the hand-maintained files here.

## Files

| Path | Responsibility |
|---|---|
| [masks_list.csv](masks_list.csv) | Local reference for network-mask columns |
| [ip_list.csv](ip_list.csv) | Local reference for IPv4 reputation columns |
| [hashes_list.csv](hashes_list.csv) | Local reference for hash columns |
| [address_blacklist.csv](address_blacklist.csv) | Local reference for address-blacklist columns |
| `*_generated.csv` (ignored) | Mutable projections regenerated from canonical storage |
| `IOC_aggregate_generated.csv` (ignored) | Standalone aggregate projection with `name;ip_address;url_match;host_match;hash` |

## Ownership and format

CSV paths are configured by `ioc.sink.artifacts[].path`; do not hand-edit generated
files or use these references as lookup/seed storage. Managed CSV import is a
separate, explicitly enabled inbox/contract workflow, not an automatic read of
this directory. See [dataframe import](../docs/guides/dataframe-import.md).

Packaged output defaults use semicolon, CRLF, quoted non-null values and an
unquoted `NULL` marker. Header/data quoting and line endings are configurable;
[CSV format](../docs/dev/csv-output-format.md) owns exact bytes, null/empty/literal
semantics, encoding and export-manifest compatibility.

Canonical/mutable-projection IDs and immutable-export reusable slots have different
contracts. [Storage](../docs/dev/storage.md),
[artifact export](../docs/dev/artifact-export.md) and
[aggregate usage](../docs/guides/ioc-aggregate.md) own those rules. File contents
alone do not identify a canonical row or establish a matching rollback point.
