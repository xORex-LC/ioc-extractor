# Application runtime resources

## Purpose and structure

Packaged defaults and Spring/Logback registration for the runnable application.
These files configure the [bootstrap graph](../java/com/iocextractor/bootstrap/README.md);
operator overrides and installation assets belong to [packaging](../../../../../packaging/README.md).

| Resource | Owner and use |
|---|---|
| [application.yml](application.yml) | Classpath baseline for typed IOC settings, processing plans, artifact schemas, logging and daemon actuator |
| [logback-spring.xml](logback-spring.xml) | Spring-profile-selected console/rolling ECS appenders |
| [META-INF](META-INF/README.md) | Early Boot processors/failure analyzers and additional configuration hints |

## Configuration and deployment boundary

Classpath defaults select oneshot, non-web runtime, JDBC canonical/service stores,
RE2/J, fail-fast pipeline policy, disabled lifecycle validity, disabled sync and
disabled import. The optional `./configs/application.yml` import is relative to
the process working directory. ConfigData profiles/imports and higher-priority
environment/system/CLI inputs still participate. Supply complete list replacements;
do not assume partial indexed overlays merge lower-priority elements.

The shipped `smb-example` profile document contains an example SMB fetch/publish
configuration. Activating a profile is a configuration choice; it does not
provision its server, credentials, share or ACLs. Production templates may choose
different runtime/lifecycle/failure-policy defaults. Read
[configuration](../../../../../docs/guides/configuration.md) and [deployment](../../../../../docs/guides/deployment.md)
instead of copying classpath development paths into an installation.

`runtime.mode=daemon` makes the environment post-processor enable web/eager beans.
`ioc.observability.mode` supplies the default active Spring profile used by
Logback. An explicit `spring.profiles.active` overrides that default, so daemon
runtime alone does not guarantee ECS file logging. `logback-spring.xml` uses the
`daemon` profile for the rolling UTF-8 ECS file and preserves console output.
File-size/age/total caps live in XML; Boot service name/version settings feed the
encoder. Do not maintain a second tuning inventory in this README.

Actuator exposes health/info on loopback by default and includes component
details. `DEGRADED` maps to HTTP 200 under the shipped policy; an HTTP success is
not proof that all remote work succeeded. Exposing this detailed endpoint beyond
loopback requires revisiting its access boundary.

## Build and verification

Maven filters the application version token; do not edit generated copies under
`target/classes`. The Boot plugin generates build-info alongside these resources;
its commit field is optional and validated when supplied at build time.
Configuration metadata is an IDE aid, not the binding/unknown-key authority.

[BuildInfoContractTest](../../test/java/com/iocextractor/BuildInfoContractTest.java),
[configuration documentation contract](../../test/java/com/iocextractor/bootstrap/ConfigurationDocumentationContractTest.java),
[Logback tests](../../test/java/com/iocextractor/observability/README.md)
and [context checks](../../test/java/com/iocextractor/bootstrap/IocConfigurationContextIT.java)
exercise filtering, hints/templates, logging and real ConfigData/list semantics.
