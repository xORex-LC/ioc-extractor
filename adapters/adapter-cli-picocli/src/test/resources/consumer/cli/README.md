# CLI consumer fixtures

## Purpose

Independent expected UTF-8 text consumed by the CLI adapter tests through
[CliGolden](../../../java/com/iocextractor/adapter/in/cli/CliGolden.java). These files pin observable
operator output. They must not be generated from the formatter being tested.

## Files

| Fixture | Consumer / meaning |
|---|---|
| [root-help.txt](root-help.txt) | Early launcher: command registration and root help |
| [version.txt](version.txt) | Early launcher: deterministic packaged test identity |
| [extract-completed.txt](extract-completed.txt), [extract-warnings.txt](extract-warnings.txt), [extract-errors.txt](extract-errors.txt) | Extraction completion, severity and row summaries |
| [fetch-dry-run.txt](fetch-dry-run.txt), [fetch-missing-source.txt](fetch-missing-source.txt) | Sync fetch counters and validation failure |
| [publish-failed.txt](publish-failed.txt) | Publish counters with a failed result |

The loader normalizes CRLF to LF, then adapts LF to the platform line separator;
other spacing and text remain significant. The sibling
[META-INF/build-info.properties](../../META-INF/build-info.properties)
resource supplies test identity. Never substitute production IOC data or release
metadata for these synthetic inputs.

## Updating and verification

Change a fixture only with an intentional reviewed output-contract change.
Check the corresponding command test and early parser model; do not overwrite
expected text simply to accept a failing assertion. Run the Surefire command in
the [test package README](../../../java/com/iocextractor/adapter/in/cli/README.md).
