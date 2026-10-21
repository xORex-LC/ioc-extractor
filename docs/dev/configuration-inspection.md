# Configuration declaration inspection

The Spring Boot configuration adapter inspects names declared under an explicit
record schema and reports ordered source observations. The service owns settings,
semantic validation, diagnostic codes, output policy and enforcement. Boot owns
binding, conversion and precedence.

## Runtime flow

```text
prepared schema + stable ordered PropertySources
  -> schema coverage + name-only source traversal
  -> immutable declarations, candidate paths and source coverage
  -> service-selected strict check / source report
  -> ordinary Boot binding and semantic validation
```

No values are read and no environment or property source is retained in a result.
Source enumeration may itself fail; that operational failure propagates. There
is no durable state, retry, reload, background work or automatic bean scanning.

## Boundaries

`adapters/adapter-config-spring-boot` depends on Spring Boot and Spring Core.
Bootstrap is its current IOC consumer; core and platform modules cannot import
it. The [module reference](../../adapters/adapter-config-spring-boot/README.md)
lists public types, supported shapes and source conventions.

One inspection returns both name outcomes and declaration observations. It does
not run two separate traversals or expose reflection/tokenizer helpers as public
API. `KNOWN` means a supported path, not a valid value. `UNKNOWN`, `AMBIGUOUS`
and `UNSUPPORTED` remain distinct; unsupported coverage cannot justify certainty
that an operator supplied an unknown key.

## Invariants

1. Source order is preserved; raw names are sorted within each source. Shadowed
   and null-valued declarations remain visible. Results describe declarations,
   not the final value's provenance, particularly for lists.
2. Candidate paths and all result collections are immutable. Ambiguous environment
   segmentation retains every candidate rather than choosing a path.
3. Fixed source categories and encounter ordinals replace arbitrary source labels
   in shared results. Names and map keys can still be sensitive; rendering stays
   service-owned.
4. Boot's aggregate is intentionally excluded. Nonenumerable sources, unqualified
   environment conventions and unsupported schema shapes disclose coverage gaps.
5. IOC retains its existing stricter spellings, converters, startup exceptions,
   legacy migration hints and external-source display selection. Its compatibility
   fallback is local; those restrictions are not universal library rules.

## Failures and extension

Invalid schema roots are programming errors. Expected name problems are result
facts. Enumeration failures propagate without being mislabeled as unknown names.
The caller chooses how either class of failure affects a service or component.

Qualify another schema shape or spelling with both independent name assertions
and the actual Boot Binder before expanding support. Add positive, unknown,
ambiguous and unsupported cases, preserve dynamic key identity and use a source
whose value getter fails to protect the name-only boundary. A new Boot baseline
requires compatibility requalification; no all-version promise is implied.

## Sources of truth

- [PropertyInspector](../../adapters/adapter-config-spring-boot/src/main/java/com/iocextractor/config/boot/PropertyInspector.java)
  and the module's public Javadoc.
- [GenericConsumerTest](../../adapters/adapter-config-spring-boot/src/test/java/com/iocextractor/config/boot/GenericConsumerTest.java)
  for unrelated-schema behavior against the real Binder.
- [IOC configuration](configuration.md) for strict service enforcement and command composition.
- [Boundaries](../BOUNDARIES.md) for inward dependency rules.

Update this document when schema/source support, coverage meaning, result
ownership or the service enforcement boundary changes.
