# Russian reusable-library guides

<a id="переиспользуемые-библиотеки"></a>
<a id="где-искать-подробности"></a>
<a id="как-поддерживать-руководства"></a>

This English-only navigation page links Russian translations of the maintained
[canonical catalog](../../libraries/README.md). All six listed artifacts require
Java 21 and use group io.github.xorex-lc; package names remain com.iocextractor.
The canonical catalog owns their dependency boundaries and release-record links.

| Artifact / recipe | Russian guide |
|---|---|
| ioc-platform-concurrency | [Keyed execution and exclusion](concurrency.md) |
| ioc-platform-diagnostics-api | [Diagnostics, policies and bounded detail](diagnostics.md) |
| ioc-platform-logging-slf4j | [SLF4J API](logging-slf4j.md), [Boot ECS recipe](logging-slf4j-boot.md) |
| ioc-config-spring-boot | [Name inspection](config-spring-boot.md), [restricted-context recipe](config-spring-boot-check.md) |
| ioc-platform-events | [JDK control-event contracts](events.md) |
| ioc-events-spring | [Explicit Spring adapter](events-spring.md) |

Read [development](development.md) before adding a mechanism or changing API.
[Publication](../library-publication.md) covers a new release and recovery from
original signed files. Independent versions are accepted policy, with Maven/CI
activation still tracked as TOOL-9; configuration's protected CI recovery remains
TOOL-8. ETL is internal and not another published library.

Guide pairs describe the same contract. Runnable Java/YAML/XML stays identical,
with English code comments. Update both languages and test against the packaged
API, distinguishing new source behavior from older released content. Synthetic
consumer checks do not qualify a second deployed service or another framework.
