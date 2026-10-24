# Controlled configuration failure reports

IOC turns configuration failures into operator reports containing fixed categories,
declared field paths and corrective guidance. This boundary covers the YAML checker,
the semantic checker and IOC's YAML/unknown-name failure analyses. It does not
sanitize arbitrary framework logs or replace service validation and recovery policy.

## Runtime flow

```text
YAML loading / Spring binding and validation / IOC registry preflight
  -> recognize structured failure
  -> retain allowed coordinates and schema paths
  -> render fixed IOC code and guidance
  -> command exits 78, or Boot presents the owned failure analysis
```

The semantic command starts a restricted configuration context. Classpath defaults,
the candidate, allowed Boot imports and effective overrides participate. Runtime
storage, transport and schedulers are not scanned. Console suppression is local to
this command and changes process streams temporarily; it is unsuitable for a shared
embedded runner. A restricted bean graph does not establish resource isolation.

## Boundaries

Bootstrap owns these reports, the `CONFIG.*` codes, field-display policy, guidance
and exit behavior. The shared configuration inspector remains a declaration-only
API; it acquires no YAML dependency, exception rendering or startup enforcement.
Spring retains binding and validation. IOC retains semantic and registry rules.

`IocConfigurationCheckFailure` is an internal immutable projection of a failed
semantic command: one kind and an immutable list of paths. It does not retain the
original throwable. YAML details retain only numeric line/column, while source
labels are supplied by the IOC caller. Unknown-name observations remain distinct
from schema-qualified binding/validation failures.

## Invariants

1. **Never copy diagnostic text from failed input processing.** Parser problem
   text, exception messages, arbitrary class names, default validation messages,
   rejected values, arguments, custom codes and arbitrary origin `toString()`
   results do not enter the controlled report. Truncation is not redaction.
2. **Preserve useful locations without dynamic map keys.** Binding uses the most
   specific structured `BindException` name. Validation uses the validation root
   plus field paths; global errors use that root. Display stops at the last
   declared IOC record field or numeric list index. Camel-case validator paths
   and kebab-case binding paths retain their spelling. Paths inside maps stop
   at the map field, so they can be less specific than the original failure.
3. **Keep name disclosure an explicit service policy.** Unknown names and raw
   environment names remain visible for typo and migration guidance. Such names,
   numeric indexes and candidate filenames are not universally non-sensitive.
   Only a typed IOC name marker can carry an inspected environment name through
   the legacy unbound-property transport; an arbitrary rejected string cannot.
4. **Do not attach the original failure to an owned Boot analysis.** Boot's
   failure reporter can log its cause at DEBUG. YAML and unknown-name analyses
   therefore carry no cause. The original failure remains with the invoking
   framework/caller, outside this report.
5. **Do not infer completed phases.** A binding failure does not establish
   successful semantic or registry checks. A validation error reports available
   violations, including multiple fields, but does not prove every other
   validator ran. Registry rejection is recognized by an IOC-owned exception
   type, never by searching its detailed message for a code.

## Failures and recovery

| Situation | Controlled result | What remains unknown |
|---|---|---|
| YAML syntax failure | `CONFIG.YAML_INVALID`, optional one-based line/column, fixed apply guidance | Name, binding, semantic and registry checks have not completed |
| Unknown or removed IOC name | `CONFIG.UNKNOWN_PROPERTY`, names, applicable `CONFIG.LEGACY_*` hints | Successful binding and later checks |
| Binding/conversion failure | `CONFIG.BINDING_FAILED`, schema-qualified path, type/token guidance | Semantic and registry results |
| Field or global validation failure | `CONFIG.VALIDATION_FAILED`, distinct paths, constraint guidance | Complete acceptance and registry results |
| Registry or processing-plan rejection | `CONFIG.REGISTRY`, fixed reference/plan guidance | Complete acceptance |
| Unrecognized runtime failure | `CONFIG.CHECK_FAILED`, fixed investigation guidance | Failure category and validation completion |

The semantic command retains its `CONFIG.SEMANTIC_INVALID` envelope and exit 78
for failed checks. `CONFIG.CHECK_FAILED` is an incomplete check, not proof of an
operator mistake. JVM `Error` is not caught by this boundary. Detailed technical
investigation needs a separate controlled diagnostic policy; copying the original
throwable into this operator report would reopen the disclosure path.

Normal startup and third-party failure analyzers/loggers may still emit their own
exceptions. In particular, Spring YAML loading may log document contents at DEBUG;
arbitrary logging appenders are outside console suppression. These reports do not
establish a whole-process secret-output guarantee.

## Extension contract

A future shared adapter must preserve phase, fixed category, optional qualified
path/coordinates, source-reference availability and incomplete/unclassified
coverage. Syntax and binding failures must leave later-phase status explicit.
Unknown/ambiguous/uninspected declarations retain the inspector's distinctions.
No throwable, supplied value, message, argument or arbitrary source object belongs
in that exported report. Services map its facts to severity, codes and actions.

This fixes the data and ownership boundary for further extraction. It does not
declare a public Java API or promise that the internal IOC projection models all
phases. Add a framework adapter only with independent consumer cases; preserve
direct dependency declarations rather than depending on accidental transitives.

## Sources of truth

- [Semantic command](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocSemanticConfigurationCheck.java)
  and [failure projection](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocConfigurationCheckFailure.java).
- [YAML details](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocYamlFailureDetails.java)
  and [YAML analysis](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocYamlConfigurationFailureAnalyzer.java).
- [Unknown-name analysis](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocConfigurationFailureAnalyzer.java).
- [Semantic checker tests](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocSemanticConfigurationCheckTest.java),
  [syntax checker tests](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocYamlSyntaxCheckTest.java)
  and [DEBUG reporter regression](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocYamlConfigurationFailureAnalyzerTest.java).

## When to update this document

Update it when exported fields, path policy, classification, phase claims, source
selection, throwable retention or controlled-output boundaries change.

## Related documents

- [Configuration](configuration.md) and [name inspection](configuration-inspection.md).
- [ADR 0016](../ADR/0016-config-preflight-strict-binding.md) and
  [configuration evolution](../ADR/0027-rollback-safe-configuration-evolution.md).
- [Bootstrap reference](../../bootstrap/ioc-app/README.md).
