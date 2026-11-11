# com.iocextractor.adapter.in.cli

## Purpose and boundary

Driving adapter package for the `ioc` command tree. It owns option parsing,
application command construction, operator output and CLI exit mapping.
Application ports own durable effects and recovery; bootstrap owns Spring wiring.
Follow the [module dependency rules](../../../../../../../../README.md).

## Structure

| Files | Responsibility |
|---|---|
| [IocRootCommand.java](IocRootCommand.java) | Root `ioc`, common help/version options and subcommand registration |
| [EarlyCliLauncher.java](EarlyCliLauncher.java) | Detached annotation-derived parser and pre-Spring command factory |
| [CliRunner.java](CliRunner.java) | Spring oneshot lifecycle and `ExitCodeGenerator` bridge |
| [ExtractCommand.java](ExtractCommand.java) | Source/dry-run arguments, correlated extraction and completion reporting |
| [ExportCommand.java](ExportCommand.java) | Profile preflight and lazy immutable-export use case |
| [SyncCommand.java](SyncCommand.java) | `sync` command group |
| [SyncFetchCommand.java](SyncFetchCommand.java), [SyncPublishCommand.java](SyncPublishCommand.java), [SyncAllCommand.java](SyncAllCommand.java) | Fetch/publish selection, execution and counters |
| [ImportCommand.java](ImportCommand.java) | Managed-import command group |
| [ImportValidateCommand.java](ImportValidateCommand.java), [ImportStatusCommand.java](ImportStatusCommand.java), [ImportReplayCommand.java](ImportReplayCommand.java) | Preview, aggregate status and replay admission |
| [ImportPreviewFileLocator.java](ImportPreviewFileLocator.java) | Seam mapping a caller-owned CSV path to an opaque preview reference |
| [HealthCommand.java](HealthCommand.java) | JDK HTTP client and Jackson health renderer |
| [ApplicationBuildInfo.java](ApplicationBuildInfo.java), [ApplicationBuildInfoReader.java](ApplicationBuildInfoReader.java), [BuildInfoVersionProvider.java](BuildInfoVersionProvider.java) | Embedded identity validation and version rendering |
| [package-info.java](package-info.java) | Package-level architectural contract |

## Startup and construction

[IocExtractorApplication](../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/IocExtractorApplication.java)
invokes the early launcher after its configuration-check paths. The launcher
builds the picocli model from the actual annotated classes, then detaches option
bindings for metadata-only parsing. Its explicit factory creates dependency-free
shells; help, version and parse failures do not call application ports.

Valid extract/export/import leaf commands and sync leaf commands continue to
Spring. Root invocation without arguments and explicit `--ioc.runtime.mode=daemon`
also continue to bootstrap; `sync` without a leaf prints early usage. Do not assume
that every incomplete command is handled early: required-option parse failures
are early, while the bare `import` group reaches its Spring-managed usage handler.

In Spring, picocli uses the injected `IFactory`. Classes with both metadata and
runtime constructors mark the runtime constructor with `@Autowired`.
`CliRunner` is conditional on `ioc.runtime.mode=oneshot` (including the default).
It stores picocli's result; bootstrap performs process termination. Do not add
`System.exit` to commands.

When adding a command, update its group's annotations, the early factory where a
shell needs special construction, the Spring construction contract and relevant
help/output fixtures together. Constructor providers must stay unresolved during
metadata handling.

## Command contracts

| Command | Local responsibility and result |
|---|---|
| `extract` | Allocates a run ID before `COMMAND_START`, forwards source and dry-run to `ExtractIocsUseCase`, renders completion, total/suppressed diagnostics, nonzero severity counts and artifact counts |
| `export` | Calls `ValidateExportProfileUseCase` before resolving `ExportArtifactsUseCase`; renders formed/recovered slice identity or a skipped export |
| `sync fetch` / `sync publish` | Validate configured selections before resolving use cases; render fetch or publish counters. Publish dry-run calls reconcile; normal publish reconciles then delivers |
| `sync all` | Validates both selections before resolving either use case; executes fetch then publish. It is an ordered operator sequence, not an atomic transaction or a document-ingest/export barrier |
| `import validate` | Resolves the preview capability and file locator; reports valid/source/accepted/rejected counts, optional contract fingerprint and diagnostic codes without committing an import |
| `import status` | Reports recovery completion, nonzero state counts and optional head/retry/code fields; successful reporting returns zero even when recovery is incomplete |
| `import replay` | Requests a new causally linked occurrence; prints its ID/sequence/state. Admission is not terminal completion. Omitted `--new-delivery` generates a UUID |
| `health` | Queries a separate daemon, renders a table or pretty-printed JSON; `--component` appends an encoded health component path |

Export recovery/leases, import canonical commits and sync idempotency belong to
application use cases. Import preview's file locator is implemented by
[DataframeImportSnapshotResolver](../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DataframeImportSnapshotResolver.java);
the caller keeps ownership of the file. A lazy provider postpones capability
resolution; it does not create or enable a missing capability.

The downstream status reader has the open [OPS-10](../../../../../../../../../../docs/KNOWN-ISSUES.md#ops-10) NULL-retry-deadline
issue. A failed `import status` or import-health query does not establish failure
of the canonical commit. Construction-only tests do not qualify this path.

### Exit and output boundaries

- picocli parse failures use usage exit `2`; execution exceptions normally use `1`.
- Extraction returns `0` for completed/warnings and `3` for completed with errors.
  Import preview returns `0` for valid and `3` for invalid results.
- Sync returns `1` when the rendered execution/summary has failures, otherwise `0`.
- Health returns `0` only for status `UP`, `1` for another reported status, and
  `2` for handled request/response failures. It reads JSON status rather than
  deriving health solely from the HTTP status code.

These codes are command-specific. Reuse `2` does not distinguish unreachable
health from invalid CLI syntax. Exceptions can also escape command-specific
checks into picocli's execution-error handler.

Import and sync render through picocli writers. Extraction, export, health and
bare root usage currently use process streams; tests must capture the appropriate
stream. Typed extract/export logging is separate from operator stdout. Do not
change output wording/order or stream routing without reviewing consumer fixtures.

### Health defaults and build identity

Early health host/port come from JVM `server.address`/`server.port`, then
`SERVER_ADDRESS`/`SERVER_PORT`, then `127.0.0.1:8081`. This path does not load Boot
YAML. Use `--host`/`--port` or `--url` explicitly for a different endpoint;
`--url` takes precedence. Spring-created health commands receive defaults through
`@Value`. Request/connect timeout defaults to five seconds. The target daemon
must already be running.

Version reads `META-INF/build-info.properties` through the JDK classloader.
`build.version` is required; an optional commit must be a full hexadecimal object
ID and an optional time must parse as an instant. Rendering abbreviates the commit
to twelve characters. Missing/malformed identity on the early path produces one
stderr error and exit `1`; no Git lookup or invented fallback identity is used.
The runnable module supplies this resource, not this adapter's production tree.

## Verification and related docs

The [test package](../../../../../../../test/java/com/iocextractor/adapter/in/cli/README.md) maps contracts and fixtures.
Operator workflows and configuration belong to the module's linked guides;
member-level guarantees belong to the corresponding Javadoc.
