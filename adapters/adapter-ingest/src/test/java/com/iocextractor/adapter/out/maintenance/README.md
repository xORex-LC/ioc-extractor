# Filesystem retention tests

Local Failsafe checks for the [maintenance package](../../../../../../../main/java/com/iocextractor/adapter/out/maintenance/README.md).

| Suite | Observable contracts |
|---|---|
| [FileSystemRetentionStoreIT.java](FileSystemRetentionStoreIT.java) | Recursive leaf listing, empty missing root, deletion, archive move and preservation of equal basenames in distinct subtrees |

The suite uses `@IntegrationTest`, real `@TempDir` files and inline bytes. Its
nested CSV names exercise generic path handling; they do not imply that the
removed partition-staging processing path is active. No module resource files
or external services are required.

These checks cover file operations and relative paths, not retention age/count
policy, scheduler cadence, ledger pinning, managed-import source/report units
or canonical expiration. They do not inject an unsupported atomic move, qualify
the ordinary-move fallback on another filesystem or establish cross-process
ownership. See the [inbound tests](../../in/ingest/README.md) for strict source
claim and protected terminal-store checks.

Run from the repository root:

```bash
make test-integration-module MODULE=adapters/adapter-ingest
```

Full acceptance uses `make verify`; [TESTING.md](../../../../../../../../../../docs/TESTING.md)
defines discovery and evidence rules.
