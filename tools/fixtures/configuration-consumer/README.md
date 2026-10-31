# Configuration library consumer

This standalone Java 21 application has no IOC parent and runs outside the
checkout with an empty Maven cache. The publication tool supplies the selected
library repository and explicit Resolver 1.9.22 group filters.

`LibraryConsumer` runs the actual
[API guide example](../../../docs/guides/libraries/config-spring-boot.md) and checks the packaged name/migration API with a collector record,
including name-only access, unknown declarations, shadowing and preserved indexes.
The tool requires matching Java/XML/YAML blocks in the EN/RU guides and also
compiles the exact Java block from the
[closed-input guide](../../../docs/guides/libraries/config-spring-boot-check.md) and its
existing independent driver. A separate JVM checks literal overrides, whole-list
replacement, safe failures, rejected imports/profiles/placeholders, ambient input
isolation and absence of component scanning.

The application owns Spring Context and SnakeYAML. Its `application-bom` profile
imports Boot's BOM. Both graphs are asserted: default mediation uses Commons
Logging 1.3.5/JSpecify 1.0.0, whereas the BOM selects 1.3.6/1.0.1. The Spring-only
upper-bound rule checks downgrade failures; the graph check also covers non-Spring
dependencies. `incompatible-spring`
selects Spring Core 5.3.39 and must fail Enforcer's upper-bound rule;
`unavailable-boot` must fail dependency resolution. These are qualification
profiles, not library-enforced version policies or a claim of support for other
Boot baselines.

Two additional empty-cache probes make the selected repository empty and place
the exact four library artifacts in an alternate repository. Filtering must
reject this fallback; an unfiltered control must compile and record the alternate
origin. Third-party dependencies and plugins remain available from Central.

Run through `make library-consumer LIBRARY_COMPONENT=configuration` after building
its bundle. See the [publication guide](../../../docs/guides/library-publication.md)
for bundle and repository options. No live collector service is started.

The publication tool supplies the guide sources; running this fixture without
that preparation is unsupported. Protected CI recovery for the published config
release remains TOOL-8; a local consumer does not close that separate follow-up.
