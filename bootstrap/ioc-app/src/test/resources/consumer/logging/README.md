# Logging consumer fixtures

| File | Responsibility |
|---|---|
| [BootRecipeMain.java](BootRecipeMain.java) | Forked consumer driver for the documented plain/Boot logging examples, including deliberate appender failures |
| [app-start.json](app-start.json) | Independent structured application-start record |
| [app-start-queries.tsv](app-start-queries.tsv) | Downstream query paths/expected observations for that record |

[LoggingBootRecipeIT](../../../java/com/iocextractor/observability/LoggingBootRecipeIT.java)
compiles the guide examples plus this source without IOC classes, supplies the
documented YAML/XML and asserts scalar types, nested paths, masks, omissions and
observable unsupported-input failures. The JSON/query fixture is consumed by
[LogbackConfigurationIT](../../../java/com/iocextractor/observability/LogbackConfigurationIT.java)
to pin IOC output compatibility from the consumer side.

These expectations are separate from producer catalogs. Keep path/type semantics
reviewed; replacing expected bytes from current output without review would hide
schema drift. [The observability test map](../../../java/com/iocextractor/observability/README.md)
owns commands and evidence limits. They are local recipes, not live collector or
cold repository qualification.
