# Boot registration and configuration metadata

| File | Responsibility |
|---|---|
| [spring.factories](spring.factories) | Registers the daemon environment post-processor and IOC/YAML failure analyzers before ordinary component wiring |
| [additional-spring-configuration-metadata.json](additional-spring-configuration-metadata.json) | Supplements annotation-processor metadata with closed-selector values and temporary SMB `encrypt` deprecation guidance |

Keep factory class names aligned with their bootstrap implementations. Registration
is required for early environment adjustment and startup failure reporting;
component scanning alone does not replace it.

The configuration processor generates `spring-configuration-metadata.json` from
`IocProperties` and merges these hints during the build. Generated
`build-info.properties` is owned by the Boot plugin, not hand-authored here.
Neither hint names nor deprecation metadata authorize otherwise-invalid config:
strict shape, converters, registry/semantic preflight and compatibility policy
remain the runtime authorities. Keep the old SMB alias only for its accepted
rollback window, absent from fresh templates.

See [runtime resources](../README.md) and
[configuration documentation tests](../../../test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java).
