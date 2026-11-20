# com.iocextractor.adapter.out.maintenance

Filesystem implementation of the application `RetentionStore` port. Follow the
[module dependency rules](../../../../../../../../README.md).

| File | Responsibility |
|---|---|
| [FileSystemRetentionStore.java](FileSystemRetentionStore.java) | Recursive leaf-file listing, delete-if-present and relative-path archive |

## Units and ownership

`list` returns regular leaf files with their modification time and scan root;
null/missing roots return an empty list. It materializes the complete recursive
listing, so it is not a bounded streaming scan. Directory buckets are not
retention entries and empty directories are intentionally left behind.

The application selects eligible entries and the delete/archive action. Age uses
file mtime; count retention pools leaves across all subdirectories of one target.
This store neither schedules maintenance nor interprets canonical lifecycle,
ledger pins or policy. Pass trusted service-owned roots and entries; ordinary
file listing is not the strict NOFOLLOW source-ownership protocol.

## Mutation contracts

Delete uses `deleteIfExists`. Archive mirrors `RetentionEntry.relativePath()`
below the archive root, creating parents. Thus equal basenames from different
subtrees retain their distinct paths. Archive permits replacement of the same
relative target and first tries atomic move, falling back to ordinary move when
atomic move is unsupported. It offers no general transaction or fsync protocol.
Filesystem failures are wrapped as `IocExtractorException`.

This leaf-file API must not clean a managed-import terminal source/report pair
independently, or substitute for immutable-slice retention. Those capabilities
have dedicated authority and whole-unit protocols. See the
[inbound protected stores](../../in/ingest/README.md) and
[application maintenance package](../../../../../../../../../../core/ioc-application/src/main/java/com/iocextractor/application/maintenance).
Bootstrap wires this store in [AppConfig](../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java).

The [test package](../../../../../../../test/java/com/iocextractor/adapter/out/maintenance/README.md) covers recursive file units and
preserved archive paths. Precise port guarantees remain in Java Javadoc.
