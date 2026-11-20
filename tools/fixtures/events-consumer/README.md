# JDK events consumer

An unrelated collector consumes the packaged metadata, publisher, observer,
no-ops and recording fixture. It runs the exact JDK example extracted from the
[guide](../../../docs/guides/events-library.md), checks validation and shallow
snapshot behavior, and rejects Spring, SLF4J and IOC bootstrap runtime classes.
There is no parent POM, project BOM, test framework or runtime dependency besides
`ioc-platform-events` and the JDK. Sources and Javadoc are requested separately.

Run through `make library-consumer LIBRARY_COMPONENT=events` with a prepared
`LIBRARY_BUNDLE`. The tool copies the fixture outside the checkout, starts an
empty Maven cache, checks the complete graph and all four hashes/origins, and
qualifies the missing-target routing failure with an unfiltered positive control.
Temporary projects/caches are removed on exit; a failed check fails the command.
This is packaging evidence, not a deployed second service or public publication.
