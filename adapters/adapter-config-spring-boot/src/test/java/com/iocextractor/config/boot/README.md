# Configuration inspection tests

## Purpose and boundary

Local contracts for [com.iocextractor.config.boot](../../../../../../main/java/com/iocextractor/config/boot/README.md).
The tests use unrelated collector-style records and synthetic property sources,
not IOC configuration. They distinguish reflection/name facts from the behavior
of the actual Spring Boot Binder on the reactor's managed dependency graph.

## Structure and coverage

| Suite | Contract |
|---|---|
| [PropertySchemaTest.java](PropertySchemaTest.java) | Invalid roots/prefixes, unsupported/recursive/unresolved shapes, qualified scalars/maps and result-field validation |
| [PropertyInspectionTest.java](PropertyInspectionTest.java) | Relaxed property/env names, ambiguity, ordered/shadowed/null declarations, composite/nonenumerable/aggregate coverage, map-key identity, enumeration failures and immutable results |
| [MigrationInspectionTest.java](MigrationInspectionTest.java) | Real Binder contrasts, removed/deprecated observations, concrete/sparse/nested indexes, schema collisions, invalid catalogs and snapshot ownership |
| [GenericConsumerTest.java](GenericConsumerTest.java) | Unrelated-schema consumption, real binding, conversion failures, list precedence and null declarations |

All four `*Test` suites run under Surefire. Their use of real Binder objects does
not create a separate Failsafe cohort. There are no local resource fixtures;
records, property maps and custom source implementations live beside each test.

## Important assertions to preserve

- Throwing value getters prove that inspection enumerates names without reading
  values. This does not constrain work inside a caller's enumeration callback.
- Independently expected canonical paths prove name matching; Binder tests show
  why a known name or complete snapshot does not guarantee valid conversion or
  effective provenance. Do not replace these oracles with the matcher's output.
- Coverage assertions preserve `UNKNOWN`/`AMBIGUOUS` versus `UNSUPPORTED`, including
  unused unsupported schema branches, intentional exclusions and empty results.
- Shadowed/null declarations and dynamic keys must remain visible. Migration
  hints preserve original diagnostics and every concrete index without applying
  replacement aliases.
- Mutation-after-construction and exact exception-identity checks protect result
  ownership and enumeration-failure propagation.

## Verification and qualification limits

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-config-spring-boot
```

The reactor gate is `make verify`; general policy is [TESTING.md](../../../../../../../../../docs/TESTING.md).
The separate [configuration consumer](../../../../../../../../../tools/fixtures/configuration-consumer/README.md)
owns packaged guide/recipe execution, standalone mediation/routing and artifact
identity checks. Local source tests do not establish cold-repository consumption,
protected publication recovery or another supported Boot version. IOC enforcement
and restricted-context behavior have their own bootstrap tests; see
[configuration inspection](../../../../../../../../../docs/dev/configuration-inspection.md).
