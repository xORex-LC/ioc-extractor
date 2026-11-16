# Local source documents

<a id="source"></a>
<a id="назначение"></a>
<a id="заметки"></a>

## Purpose and boundary

Operator-provided input documents may be placed here for manual extraction.
Document payloads are not distributed with a clean checkout. Treat them as input
data; synthetic tests and fixtures belong to their test resources or ignored
`.dev/` workspace, not this directory.

## Reading and supported formats

The `extract --source` option selects an input path; daemon intake uses its
configured inbox. Application callers read through SourceReader and the
[Tika adapter](../adapters/adapter-source-tika/README.md), with automatic format
recognition and configurable charset handling. HTML, PDF, DOCX and XLSX have
contract witnesses; this does not guarantee arbitrary parser inputs or resource
capacity. Other installed parser formats remain best effort.

[Processing](../docs/dev/processing.md) explains extraction and attribution;
[resource sizing](../docs/guides/bulk-resource-sizing.md) explains admission and
known limits. For a reproducible document without private source data, use the
fixture commands in the [developer tools](../tools/README.md).
