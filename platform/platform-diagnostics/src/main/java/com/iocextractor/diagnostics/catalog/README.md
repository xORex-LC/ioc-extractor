# com.iocextractor.diagnostics.catalog

## Responsibility and sources

Explicit product-code enumeration and flat documentation values, with no runtime
reflection/discovery or application configuration dependency.
[package-info.java](package-info.java) records the package boundary.

| Source | Responsibility |
|---|---|
| [DiagnosticCatalog](DiagnosticCatalog.java) | Enumerable `List<IocDiagnosticCode>` contract |
| [DiagnosticCatalogs](DiagnosticCatalogs.java) | Explicit aggregate of the twelve subsystem enum groups |
| [DiagnosticCatalogEntry](DiagnosticCatalogEntry.java) | Flatten ID/category/severity/impact/message-key/template from one IOC code |

The static aggregate registers enum arrays in source order. `all()` returns the
immutable registered list; `entries()` maps that order to fresh flat records.
This is the source for the [generated Markdown catalog](../../../../../../../../../docs/DIAGNOSTICS-CATALOG.md).
It is not a registry of arbitrary runtime consumer codes, a message translator,
a persisted-code decoder or an emission router. `DiagnosticCatalog` is a separate
list contract; callers do not register plugin catalogs through it in the current
static aggregate.

## Validation and generation

[DiagnosticCatalogTest](../../../../../../test/java/com/iocextractor/diagnostics/DiagnosticCatalogTest.java)
checks ID/message-key uniqueness, ID/category alignment, required metadata and
registration completeness by discovering compiled enum constants in the codes
package. The test uses file-backed compiled classes, unlike the runtime aggregate.
This is a local test guard, not a runtime classpath/plugin scan or an ArchUnit rule.

[DiagnosticCatalogDocumentationTest](../../../../../../test/java/com/iocextractor/diagnostics/DiagnosticCatalogDocumentationTest.java)
owns Markdown generation, including column order and pipe escaping. Its normal
run compares generated output with the committed file, normalizing CRLF/trailing
whitespace. Validate from the repository root:

```bash
make test-one MODULE=platform/platform-diagnostics TEST=DiagnosticCatalogDocumentationTest
```

After an intentional code/registration change, regeneration uses the same test
with its explicit update property; review the resulting diff:

```bash
./mvnw -B -ntp -pl platform/platform-diagnostics -am test \
  -Dtest=DiagnosticCatalogDocumentationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dioc.docs.update=true
```

Do not hand-edit or translate the generated catalog. The
[runtime reference ratchet](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/CatalogReferenceRatchetTest.java)
separately detects production bytecode references, with its reviewed allowlist;
a reference can be an enum-switch access and is not evidence of sink emission.
Producer-specific and log-wire tests must verify real conditions separately.
See the [module reference](../../../../../../../README.md) for dependency
and orchestration boundaries.
