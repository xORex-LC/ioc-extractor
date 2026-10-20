# Tool fixtures

Standalone consumer projects used by repository publication tooling. These are
not reactor modules and must resolve packaged artifacts without reactor classes.

- [library-consumer](library-consumer/README.md): keyed execution public contracts.
- [diagnostics-consumer](diagnostics-consumer/README.md): results, bounded counts
  and delivery failure examples from the diagnostics guide.
- [logging-consumer](logging-consumer/README.md): actual SLF4J guide examples,
  transitive API dependency and deliberate missing/unavailable dependency failures.
- [logging-boot-consumer](logging-boot-consumer/README.md): actual application-owned
  Boot Java/YAML/XML recipe and JSON/status checks from packaged JARs.
