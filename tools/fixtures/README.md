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
- [configuration-consumer](configuration-consumer/README.md): packaged inspection
  and migration, exact closed-input guide, application BOM mediation and
  selected-repository failure/control probes.
- [events-consumer](events-consumer/README.md): JDK-only metadata, recording and
  no-op contracts, exact guide and selected-repository failure/control probes.
- [events-spring-consumer](events-spring-consumer/README.md): two-artifact closure,
  real Spring listeners, exception/observation boundaries, API exclusions,
  mediation and missing-core routing failures/controls.
