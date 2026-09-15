---
title: "Existing-code library exploration"
version: "0.3.0"
goal_id: "R030-LIB"
status: "exploration"
document_type: "Interview worknote"
source_of_truth: false
language: "en"
---

# Existing-code library exploration

## Purpose and working agreement

Identify existing IOC Extractor implementations that justify separately
consumable libraries. Evaluate actual responsibility, consumers, semantics,
dependency closure, public API and compatibility costs before admission.
This is not a design exercise for new collector features.

The owner requests one plain-language question at a time. Before asking:

1. Inspect a concrete existing implementation and its callers.
2. Explain what it currently does and what is product-specific.
3. Project its possible use in another service without assuming that need.
4. Ask the question that establishes whether those existing semantics are useful.
5. Record the answer, observations, proposal, risks and next question here.

Owner statements, assistant proposals and verified implementation facts must
remain distinguishable. No module extraction, publication, new version train
or candidate admission is authorized by this interview alone. Keep the current
product/library lockstep policy. Existing module boundaries do not themselves
justify publication.

## Sources and baseline

- [Release plan](../engineering-release.md).
- [R030-LIB contract](../goals/R030-LIB-shared-libraries.md).
- [Candidate inventory](../evidence/shared-code-inventory.md).
- [LIB-1 evidence](../lib-1-concurrency-worknote.md).
- [Publication guide](../../../guides/library-publication.md).

Repository checked on 2026-09-13: `release-0.3.0`, HEAD
`0df7183fcc02cfd010104733c3608e710b059be9`, clean before creating this worknote.
R030-LIB is already verified by the concurrency pilot; publishing more modules
is not a prerequisite for closing that goal. The existing inventory remains
unchanged by these provisional assessments.

## Owner context: future consumers

The owner plans a single feeds-collection service with several aggregators,
each configured separately. Further services will deliver prepared feeds to
proxies, DNS servers, firewalls and other protection targets. Collector services
will configure, manage and monitor those targets under a collector-management
server. These are intended consumers, not implemented or qualified integrations.

The exploration must consider this broader service family rather than only the
feeds collector. Shared technical behavior is relevant; common business logic
is not presumed.

## Interview record — 2026-09-13

### 1. Failure isolation for configuration

**Question asked:** Should a configuration error stop the whole service or only
the affected task/connection?

**Owner answer:** A configuration error for one aggregator should not stop the
single collection service or its other aggregators. The same applies to delivery
collectors. Prefer disabling the affected task/connection. A library should
provide tools usable in different scenarios, adding capabilities only when
needed; extract what already demonstrably fits.

**Observation/proposal:** Separate detecting and describing problems from the
service's decision about which work may run. A global configuration failure
could still prevent startup; this distinction was proposed by the assistant,
not separately confirmed as a complete owner policy.

**Existing-code limit:** IOC Extractor's strict startup preflight currently
rejects erroneous configuration globally. It does not implement independent
activation of correctly configured aggregator instances. Do not describe that
future behavior as existing reusable functionality.

**Risk:** Publishing the current startup behavior as a mandatory shared policy
would conflict with the owner's intended isolation. Extractable validation
mechanics and diagnostic results must be assessed separately from orchestration.

### 2. Reload investigation and correction of scope

**Question asked:** Must corrected settings apply without restarting the service?

**Owner answer:** Recognized this as reload, noted that the current project
probably lacks it, and requested investigation of Spring/Java ecosystem tools.
Prefer existing tools and add custom common logic only where needed. This was
not an instruction to implement reload or a commitment to require it.

**Investigation results:** No general runtime configuration reload was found.
`IocProperties` uses Java records; existing preflight/checker is startup-oriented.
Spring Cloud Context provides refresh mechanisms, Config provides centralized
configuration, and Bus can distribute refresh notifications. Apache Commons
Configuration provides reload detection/controllers. None alone establishes
the application's safe replacement policy for running tasks/connections.
Spring Cloud documents limitations for constructor-bound records and
`HikariDataSource`. These were documentation/code findings, not a prototype.

References consulted:

- [Spring Cloud Context](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html).
- [Spring Cloud compatibility and components](https://spring.io/projects/spring-cloud/).
- [Commons Configuration reload](https://commons.apache.org/proper/commons-configuration/userguide/howto_reloading.html).

**Withdrawn question:** Keep old settings or disable a working connection when
an attempted reload is invalid? The owner correctly identified this as further
design of an absent feature. It has no answer and is not pending in this interview.

**Owner correction:** Start each question from inspected CURRENT code and its
reuse, independence and API. Do not steer the interview toward implementing
future features.

**Disposition:** Reload is out of the present extraction scope. Do not create
a reload library, controller or policy as part of this exercise. Revisit only
under an explicit future implementation task.

## Provisional candidate assessment

These are assistant assessments from the initial code review, not owner admissions.

| Candidate | Existing useful mechanics | Boundary or prerequisite | Disposition |
|---|---|---|---|
| LIB-2 diagnostics | Diagnostic values, result plus diagnostics, collection, sinks | Closed IOC categories, service exception root and suppression catalog coupling | Continue genericity review |
| Strict configuration subset | Shape checking, environment-name matching, unknown-key reporting | Fixed `ioc` prefix/schema, Spring integration and startup-wide rejection | Investigate existing subset only |
| LIB-3 observability subset | Typed fields, value normalization, nested MDC restoration | IOC field/action enums and ETL observer dependency | Genericity review |
| LIB-4 ETL | Sequential stages, envelopes, observation and failure evaluation | Diagnostics/errors closure; second consumer semantics unconfirmed | Defer |
| LIB-5 local events | Framework-free publish-only control API | Local signals do not establish inter-service delivery contracts | Defer |
| IOC domain SDK | JDK-only extraction/refang/classification | Needs a consumer that actually embeds IOC processing | Conditional; not generic platform |
| SMB adapter | Sessions, transport operations and change notifications | Application ports and sync/import semantics | Reject whole module as-is |
| Build/test reuse | Existing tooling and contract fixtures | Reactor-specific scope and evidence; distinct from runtime libraries | Separate investigation if needed |

Do not publish whole diagnostics/observability modules, a standalone product
exception root or an umbrella shared JAR merely to increase library count.

## Publication tooling observations

The prior analysis in this conversation ran `make library-publication-test`:
16 tests passed, including disposable-key signing and partial-publication recovery.
It also checked successful protected run `34233605995` and fetched the Central
POM for `io.github.xorex-lc:ioc-platform-concurrency:0.3.0-rc.1` (HTTP 200,
no parent or dependencies). It did not repeat live publication/cold consumers.

Before a second admitted artifact, review these actual pilot constraints:

- Tool constants, source paths, archive markers, component tag and consumer
  fixture are specific to concurrency.
- `prepare()` rejects all POM dependencies; dependency-bearing candidates need
  an explicit allowed closure and repository-resolution qualification.
- Current consumer recompilation is not a complete previous-release binary/API
  compatibility check.
- Workflow artifacts have 90-day retention; long-term recovery requires an
  explicit preservation arrangement.

These are follow-ups for an admitted publication unit, not changes authorized now.

## 3. Inspected implementation: result plus diagnostics

**Code inspected:** `platform/platform-diagnostics` classes `Result`,
`Diagnostic`, `DiagnosticCode`, `DiagnosticSink`, and
`adapters/adapter-csv` class `CsvArtifactPreparer.prepare`.

**Current behavior:** The preparer catches individual `RowMappingException`s,
collects structured diagnostics and returns an `ArtifactWritePlan` for prepared
rows together with those diagnostics in `Result<ArtifactWritePlan>`. Producing
this result does not authorize durable writes; the caller's failure policy is
evaluated separately. `Result<T>` copies its diagnostic list, supports error/fatal
queries and value mapping, and permits a null value. `DiagnosticSink` is an
observational port whose delivery must not alter processing control flow.

**Reuse projection:** Another service could return its own result together with
machine-readable problems without depending on IOC rows, CSV or storage. The
minimal candidate is a diagnostic value/code contract plus `Result<T>`; add sinks
only if their use is established. Target closure could be JDK-only after removing
product coupling, but the current `DiagnosticCode.category()` still exposes a
closed product-oriented enum. This is a required API review, not a completed split.

**Risks:** Do not confuse partial preparation with permission to apply partial
changes to a protection target. Do not turn these in-process objects, including
their possible `Throwable`/arbitrary context values, into an assumed wire format.

**Question asked:** Should future services return a usable operation result
together with a structured list of problems for the calling code to inspect and
decide what to do, as the current preparer does, or is logging those problems
sufficient for their intended use?

**Owner answer:** Yes, this behavior will be useful in future services.

**Conclusion:** Reuse of result plus structured diagnostics is confirmed as a
consumer need. Continue LIB-2 API/dependency review around this existing behavior.
This does not yet admit the entire diagnostics module, its exception hierarchy,
failure policies, delivery adapters or wire serialization.

## 4. Inspected implementation: diagnostic delivery

**Code inspected:** `DiagnosticSink`, `CollectingDiagnosticSink` and
`ResilientDiagnosticSink`.

**Current behavior:** Producers can emit a diagnostic through the single-method
`DiagnosticSink.emit(Diagnostic)` port. The collecting implementation retains
diagnostics in memory for tests/report assembly and is not thread-safe. The
resilient decorator catches runtime delivery failures, reports them through a
separate SLF4J logger and contains runtime failures of that fallback as well.
Diagnostic delivery is observational; it must not change the operation outcome.

**Reuse projection/proposal:** A caller-provided diagnostic receiver could let
different services choose how to observe the same diagnostic values. The port
could accompany the generic model without a logging dependency; the existing
SLF4J-dependent decorator should be evaluated separately. Do not infer durable
delivery, a remote protocol or an audit guarantee from this API.

**Risk:** Best-effort observation is inappropriate for records whose successful
delivery is a prerequisite for performing the operation. The current collector
also supplies neither concurrent access nor a retained-item bound.

**Question asked:** Would future services benefit from supplying their own
receiver for diagnostics as an operation runs, with receiver failure not stopping
the operation, as the current observational sink contract requires?

**Owner answer:** Yes, this behavior will be useful.

**Conclusion:** A caller-supplied observational diagnostic receiver and isolation
of its failures are confirmed needs alongside result plus diagnostics. The port
alone does not enforce containment; evaluate the existing resilient implementation
and its SLF4J dependency separately during API admission. This answer does not
authorize a remote delivery system or an audit persistence guarantee.

## 5. Inspected implementation: failure decisions

**Code inspected:** `FailurePolicy`, `FailureDecision` and the
`PipelineRunner` call to `rejectIfRequired` after a stage.

**Current behavior:** A policy evaluates accumulated diagnostics without I/O or
mutation and returns continue/stop with the triggering diagnostic. Existing
factories stop on ERROR or FATAL, or allow ERROR and stop only on FATAL.
The caller chooses when to evaluate and how to enforce the decision. The
interface also permits caller-defined policies. These mechanics do not depend
on Spring, task execution or transport.

**Reuse projection/proposal:** Include the small decision contract and existing
severity policies in the diagnostics candidate if callers need reusable decisions
at their own processing checkpoints. Keep the scope of stopping and resource
management in each service, and do not extract the ETL runner merely to use a
policy. Review `Notification` separately because its throwing convenience method
couples it to the current diagnostic exception hierarchy.

**Risk:** A stop decision does not undo earlier side effects or stop the entire
service. The name `failFast` does not imply immediate interruption when a
diagnostic is produced; evaluation happens at caller-selected checkpoints.

**Question asked:** Would the existing choice between stopping on any error
and continuing through ordinary errors until a fatal problem be useful as
reusable rules, with each service choosing the rule for a particular operation?

**Owner answer:** Yes, it will be useful.

**Conclusion:** Existing severity-based policies and caller-selected evaluation
are confirmed reuse needs. Include them in the proposed LIB-2 review scope with
result plus diagnostics and observational delivery. This is confirmation of
usefulness, not approval of the current full API or authorization to extract it.

## 6. Inspected implementation: bounded diagnostic retention

**Code inspected:** `BoundedNotification`, including its retention budget,
severity tracking, suppression summary and product catalog dependency.

**Current behavior:** Retains a limited set of element/run diagnostics, counts
suppressed occurrences by severity, and emits a synthetic suppression summary.
When the budget is exhausted, a first ERROR signal (if no error-or-worse has
been observed) or first FATAL signal can replace a lower-severity retained item.
OPERATION diagnostics bypass the budget. This is retention control, not
deduplication, logging rate limiting or a strict total memory bound.

**Reuse projection/proposal:** Useful for large batches where retaining every
individual problem is unnecessary but a useful sample and accurate severity
counts are required. Assess as an optional part of the diagnostics library;
do not impose lossy retention on every `Result<T>` or diagnostic receiver.

**Risks/boundary:** The current synthetic diagnostic depends directly on
`PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED`; extraction must resolve this
catalog coupling and review impact categories. The accumulator is mutable and
not synchronized. Its budget does not limit diagnostics already sent to sinks
or lists constructed by a producer before accumulation. It is unsuitable as
the sole record when every individual error must be retained.

**Question asked:** Would future services benefit from this existing mode:
retain only a limited set of individual problems plus counts of the remainder,
while preserving error/fatal signals, instead of keeping every problem in memory?

**Owner answer:** Yes, it will be useful.

**Conclusion:** Optional bounded retention and suppression counts are confirmed
reuse needs. Include this behavior in the LIB-2 assessment, retaining the
documented limits above; do not silently turn it into mandatory lossy retention
or claim a strict total memory cap.

## 7. Inspected implementation: rendering diagnostic messages

**Code inspected:** `DiagnosticRenderer`, `TemplateDiagnosticRenderer` and
`DiagnosticContextFormatter`.

**Current behavior:** A renderer reads the default message template from the
diagnostic code and replaces `{key}` placeholders with values from its context.
Missing values leave the placeholder unchanged. Callers can supply a formatter
for each substituted value. The default uses `String.valueOf` without redaction.
The mechanism depends on the diagnostic model and JDK, not Spring or a logging
backend. Bootstrap wires it into the diagnostic logging sink with an explicit
redacting formatter.

**Reuse projection/proposal:** Keep human-readable presentation separate from
machine-readable diagnostic identity. A small optional renderer could accompany
the admitted model; each service supplies its own codes/templates and formatting
policy. Do not introduce a new template engine or localization framework.

**Risks:** Existing rendering does not guarantee secret removal, localization,
HTML escaping or a transport representation. The formatter is an extension point,
not a universal security policy. Product-specific templates/catalogs remain
outside the generic library.

**Question asked:** Should the shared diagnostics library also offer the
existing optional template-to-text rendering mechanism, with messages supplied
by each service, or should textual presentation remain entirely with callers?

**Owner preference:** Prefer each service to form its own text, accepting some
local logic to keep the library focused. The owner invited assessment rather
than presenting this as an absolute prohibition on all future rendering reuse.

**Assistant assessment/recommendation:** Keep rendering out of the initial
diagnostics publication unit. Presentation is a service responsibility, not
accidental clutter in that service. A small generic renderer is not inherently
a catch-all design, but its shared demand is unconfirmed and it introduces
template/formatting behavior that would need a public compatibility contract.
Keep the current IOC renderer locally without duplicating or deleting it.

**Additional live-code finding:** `DiagnosticCode` currently requires
`messageKey()` and `defaultMessageTemplate()`. `DiagnosticCatalogEntry` also
copies these fields. Excluding only the `render` package would therefore leave
presentation obligations in the purportedly generic API. Proposed admission
must separate these product presentation/catalog responsibilities as well,
preserving IOC logging and catalog generation through local code. This is an
API boundary proposal, not an implemented signature change.

**Working scope:** Structured diagnostics, results, observational delivery,
failure decisions and optional bounded retention have confirmed reuse value.
Text rendering is proposed to stay service-local for the first unit. No extra
renderer artifact is justified now. Shared runtime exceptions, category/impact
contracts and exact public types still require review before admission.

**Next step completed:** The owner authorized a code-backed boundary proposal.
See [LIB-2 design](lib-2-diagnostics-design.md) for the type-by-type disposition,
proposed public API, independent consumer illustration and migration/validation
plan. No unanswered feature question remains in this section. The proposal
requires review before implementation and does not change candidate admission.

## 8. Boundary review: category ownership

**Question asked:** Should future services own their categories or use a common
category set for the entire system?

**Owner answer:** Agrees with the recommendation for service-owned categories
and a common diagnostic format.

**Conclusion:** Category separation in the LIB-2 proposal is accepted. Generic
diagnostics must not mandate an IOC taxonomy. Adding service categories must
not require changing the library. Preserve IOC category-based behavior locally;
the precise metadata helper remains an implementation proposal.

## 9. Boundary review: diagnostic equality

**Code inspected:** `Diagnostic.equals/hashCode` and the existing design's
behavioral-contract section. Equality uses code object and context only;
severity, timestamp and cause are ignored. Accumulating diagnostics in the
current lists does not itself deduplicate them.

**Observation:** Equal code/context diagnostics at WARN and ERROR compare equal.
This affects sets and other equality-based processing, although severity counts
in current list-based accumulators retain separate occurrences. Changing equality
is a behavioral change requiring consumer review, including pipeline prefix
comparisons; no change is authorized or implemented here.

**Question asked:** When the same problem with the same details appears first
as a warning and then as an error, should a shared comparison consider them the
same diagnostic or distinguish them because severity changed?

**Owner answer:** They must be distinguished by severity.

**Conclusion:** Approve adding effective severity to diagnostic equals/hashCode
in the library design. Target equality is code object + context + severity.
Current code is unchanged. Time and cause remain excluded in the proposal;
the answer does not authorize automatic deduplication or occurrence identity.

**Migration evidence:** PipelineRunner.delta compares prefixes with List.equals.
Include a regression proving that replacing a prior diagnostic with one of a
different severity is rejected by the append-only check. Update the current
Diagnostic equality test deliberately and verify set behavior without assuming
unequal objects must have different hash codes.

**Next step:** Consolidate the reviewed boundary and assess remaining technical
admission details; no unanswered question remains in this section.

## 10. Diagnostics technical design checkpoint

The owner authorized completion of technical review before returning to the
configuration interview. The [proposal](lib-2-diagnostics-design.md) now records
source-level dependency closure for 15 diagnostics types plus the resilient
sink, exact product seams, the remaining caller of `throwIfRejected`, and a
coherent migration/qualification sequence. The existing publication ban on
runtime dependencies can remain for this JDK-only candidate. No publication
support for third-party dependencies is needed just for LIB-2.

Conclusion: enough evidence to retain the boundary proposal and move the
interview to another existing capability. No production extraction or prototype
was performed; previous focused tests cover existing code only. Admission,
compiled consumer and runtime migration evidence remain future work.

## 11. Existing capability: unknown configuration names

**Code inspected:** `IocUnknownConfigurationPreflight`, its leaf-type test,
and its calls to `IocEnvironmentPropertyMatcher`/configuration shape helpers.

**Current behavior:** Before binding, the preflight examines enumerable property
sources, handles environment names separately and checks names within `ioc.*`
against IocProperties. It skips the aggregate `configurationProperties` source.
Unknown names are collected and currently cause a startup exception. This
detects misspelled settings that might otherwise leave a default value active.
It is not value validation, a YAML parser or a universal scanner of arbitrary
non-enumerable property sources.

**Reuse projection:** Another service could supply its own reserved prefix and
record schema to reuse name checking. Prefix/schema generalization and returning
problems separately from startup-wide rejection are extraction adaptations;
the current implementation does not already offer that reusable API or isolate
individual connections. Caller-owned failure scope follows interview answer 1.

**Question asked:** Should unknown parameter names inside a service's own
configuration namespace be reported as configuration errors, even when the
service could use a default value and otherwise continue running?

**Owner answer:** No mandatory error classification; the approach must be
individual to the situation.

**Conclusion/proposal:** Separate name recognition findings from severity and
reaction. A shared checker could return unknown names; the caller decides
whether to reject, warn or ignore. These choices are proposed examples, not
three required library modes or an agreed default. The answer does not yet
confirm that every future service needs the checker.

**Current-code seam:** `postProcessBeanFactory` collects unknown names and then
throws unconditionally. Extracting detection from that enforcement is necessary
for reuse. Preserve strict IOC startup policy locally; this answer does not
authorize weakening the current service or implementing configurable enforcement.

## 12. Existing capability: configuration override provenance

**Code inspected:** `IocConfigurationOverrideReporter.reportOverrides` and
`effectiveOverrides`.

**Current behavior:** Walks enumerable property sources in their supplied order,
skips baseline and aggregate sources, canonicalizes names and retains the first
source label for each external key. At startup it logs names and source labels,
not values. Its migration warnings use the product migration catalog.

**Reuse projection:** Other Boot services could expose which external source
supplied a setting, helping diagnose a file value overridden by the environment
or command line. The ordered source traversal/name normalization is reusable;
the fixed prefix, logging text, startup hook and migration catalog stay separate.
It is a name/source report, not proof of full bound-object provenance for complex
list/map overrides. Do not build another binding/precedence engine.

**Question asked:** Would future services benefit from reporting which source
supplied each externally overridden setting, without exposing its value?

**Owner answer:** Yes, it will be useful.

**Conclusion:** Value-free external-setting provenance is a confirmed reuse
need. Assess the existing traversal and canonicalization together with name
checking; keep product logging/migration messages outside the candidate.
This does not confirm exact provenance for every nested bound value or authorize
new binding behavior.

## 13. Existing capability: named handler references

**Code inspected:** `ConfigRegistryPreflight` methods
`validateClassifyPredicates`, `validateSinkArtifacts`, `validateColumnTransforms`
and `rejectUnknown`.

**Current behavior:** Configuration selects predicates, providers, filters and
transforms by name. Preflight compares them with registered names, accumulates
errors with indexed configuration paths and fails startup after the checks.
Transform specifications use the product's `name:arg` convention. The same class
also checks product-specific deferred ID column rules.

**Assessment:** Entire class stays product-specific. The generic membership
check alone is small and does not justify a standalone library. A possible
reuse seam exists only if other services also select registered handlers through
configuration and share the validation/reporting needs. Do not create a plugin
loader, generic rules engine or shared registry framework from this example.
Its current messages include supplied names and allowed values; it must not be
marketed as universally value-free validation.

**Question asked:** Will future services choose processing handlers by names
in configuration, as IOC Extractor chooses filters/transforms, or will handler
selection normally be fixed in service code? The question establishes whether
the existing registry-reference validation pattern has another consumer.

**Owner answer:** Such a mechanism may be useful.

**Conclusion:** Potential reuse acknowledged, not a confirmed second concrete
consumer or admission of a registry framework. Retain as a conditional helper
candidate within configuration review. Product registries, traversal rules and
handler execution remain local. Membership checking alone does not justify
another artifact.

## 14. Existing capability: configuration-only validation

**Code inspected:** `IocConfigPreflight.validate` and
`IocSemanticConfigurationCheck.validate`.

**Current behavior:** Semantic checks use Spring Validator/Errors to collect
multiple problems in bound IocProperties; the rules themselves concern product
storage, lifecycle, sync, ingestion and import. The configuration-only entry
point validates a candidate in a restricted context without normal service
startup. This is separate from runtime reload or live endpoint qualification.

**Assessment:** Do not extract the product validator or recreate Spring's error
collector. Evaluate whether the restricted configuration-check entry pattern
has independent consumer demand; product schema, validators, command wiring
and its current exit/error conventions remain explicit migration seams.
Configuration-only checking does not prove credentials or endpoint reachability.

**Question asked:** Would future services need a separate command to check
configuration and report problems without launching normal work or connecting
to their targets, as IOC Extractor already supports?

**Owner answer:** Yes, it will be useful.

**Conclusion:** Configuration-only command behavior is a confirmed reuse need.
This does not by itself admit the entire checker as an embeddable library.
Keep schema, semantic rules, command/exit policy and startup composition owned
by each service. Preserve IOC's current restricted validation behavior.

**Additional code review:** `SemanticCheckConfiguration` explicitly binds
IocProperties and imports ConfigPreflightConfiguration. The checker also filters
Spring listeners/arguments and temporarily replaces process-wide System.out/err
inside a static synchronized block. That lock serializes checker calls, not
unrelated output from other threads. This design belongs to an isolated command
path; copying it into a generic in-process validator could suppress unrelated
service output. Do not generalize it unchanged or infer live reload support.

## 15. Boundary review: Spring Boot dependency

**Existing evidence:** Property-name canonicalization uses Spring Boot types;
source traversal uses Spring Environment; semantic checks use Spring Validator;
the separate command builds a restricted Boot context. Only lexical/reflection
shape primitives are already JDK-only. This candidate differs from diagnostics.

**Assessment/proposal:** If the intended consumers use Boot, prefer a narrow
Boot-specific integration module over an invented independent configuration
framework. Extract a separate framework-free core only when real reusable code
and a concrete non-Boot consumer justify it. No module split is admitted yet.

**Question asked:** Are the intended consumers of these configuration helpers
Spring Boot services, or is reuse by plain Java applications without Spring
also an actual planned need?

**Owner answer:** Spring is definitely required; no non-Spring consumer need
was identified. In the context of the question, proceed with Spring Boot as
the target integration environment.

**Conclusion:** A Boot-dependent configuration integration module is appropriate
for further review. Do not create an independent configuration engine or split
out a JDK-only core solely for hypothetical non-Spring consumers. This answer
does not add Spring dependencies to the separate JDK-only diagnostics proposal
or relax IOC application/domain framework boundaries.

## Configuration interview checkpoint

The current answers support preparing a concrete existing-code boundary:

- value-free reporting of external configuration sources: confirmed useful;
- separate configuration-only check command: confirmed useful, with command
  isolation and service-owned composition;
- unknown-name recognition: individual severity/reaction required, not a
  mandatory shared rejection policy; universal consumer need not inferred;
- named-handler reference checking: potentially useful, conditional helper;
- Spring Boot integration: selected context; no independent config framework;
- schema, semantic rules, templates, migration hints and final failure scope:
  remain service-owned; reload remains out of scope.

**Design checkpoint, 2026-09-13:** The
[configuration boundary proposal](configuration-library-design.md) records the
existing-type review, supported naming/source limitations, proposed API and
dependency direction, diagnostics integration, command isolation, publication
tooling gaps and implementation slices. Recommendation: one Boot adapter library
for name inspection and ordered source observations; service policy, validators
and command composition remain local. The reporter does not establish final
bound-value provenance. Existing publication tooling needs dependency-aware
qualification before it can admit this library.

**C0 checkpoint, 2026-09-13:** The owner clarified that no second configuration
exists and accepted a synthetic schema as a technical probe, not as design of a
future service. The requested [C0 analysis](configuration-c0-analysis.md) reproduced
54 scenarios against Boot 4.0.8. It confirms a useful independent mechanism but
rejects extracting the classes unchanged: relaxed-name mismatches, unsupported
schema/source cases and inaccurate effective-provenance claims require explicit
correction or scope limits. The fixture is not evidence of a second real consumer.

**Workflow clarification:** The owner asked whether the work had moved into
implementation. Diagnostics and configuration are both at the design-proposal
stage; C0 was additional configuration research, not extraction. The owner then
agreed to continue examining other existing mechanisms. C1 remains a future
implementation option, not the active task. No extraction/publication is underway.

## 16. Next candidate: operational logging and execution context

**Evidence refreshed:** `platform-observability` production helpers, module POM
and README, current bootstrap event-listener usages, and the shared-code inventory
at commit `0df7183fcc02cfd010104733c3608e710b059be9`.

**Recommendation:** Examine the existing LIB-3 candidate next. Its small logging
mechanics have immediate cross-service applicability and are close enough to
the diagnostics boundary to clarify their responsibilities. This is a candidate
review, not approval to publish the entire module.

Existing mechanisms to discuss separately:

- `MdcScope`: temporarily adds or hides operation metadata in the current thread's
  logging context, supports nested scopes and restores touched keys on close.
  Current bootstrap fetch/publish/export listeners establish scopes from event
  metadata. It does not itself propagate context between worker threads or services.
- `LogEvent` / `LogValueNormalizer`: validates structured field scalar types,
  writes event-local fields through SLF4J and temporarily hides matching MDC keys
  so one event does not carry competing contextual values.
- `SensitiveLogValueSanitizer`: removes URL user-info and query content. It
  preserves fragments and is not a general secret scrubber; independent reuse
  needs its precise policy reviewed.

**Coupling:** `MdcScope` and `LogEvent` expose the IOC `LogField` enum; LogEvent
also accepts `EventAction`/`EventOutcome`. `LoggingPipelineObserver` creates the
module's ETL dependency. Keep the IOC field/action catalog, pipeline observer,
message text and runtime logging configuration outside a prospective generic
subset. Main code uses SLF4J; Logback is a test dependency in this module.
Compare the custom behavior with framework facilities before admitting wrappers.

**Why this before ETL/events:** ETL needs a demonstrated common processing
contract and has diagnostics/error dependencies; the event module is deliberately
a local publish-only port, not a transport between services. Logging scopes and
typed event fields can be assessed without inventing future business workflows.

**First discussion point:** Would future services benefit from setting task and
connection identifiers once around an operation so its log entries can carry
that context, with previous context restored afterwards? This asks about the
existing scope behavior, not distributed tracing or a new logging platform.

**Owner answer:** Yes, useful. The owner asked whether this behavior is really
specific to logging.

**Clarification:** An operation's context is broader than logging. The existing
`MdcScope`, however, is specifically a scoped adapter over SLF4J MDC and accepts
the logging field catalog. In `RemoteChangeFetchListener`, business inputs travel
explicitly in the event and fetch command; a separate scope copies selected event
metadata into MDC inside the worker. The logging context is not the source of
business inputs and is not an automatic cross-thread context carrier. Assess the
existing logging adapter for extraction; do not infer a generic execution-context
library from its utility. Other existing context carriers can be reviewed on
their own contracts. No new implementation or publication work is authorized.

**Owner reconfirmation:** After the logging-versus-operation-context distinction
was explained, the owner confirmed that the existing logging scope is useful.
This confirms reuse interest, not a broader execution-context API.

## 17. Structured log fields and declared scalar types

**Existing evidence:** `LogEvent.field` delegates to `LogValueNormalizer` before
writing fields through SLF4J. `LogValueType` supports STRING, LONG and BOOLEAN.
Integral Byte/Short/Integer/Long values become Long; a numeric string is rejected
for a LONG field. Null omits a field. Current import and ingestion observers use
this mechanism for counts, durations, identifiers and outcomes. Existing
`LogEventTest` asserts typed values and rejection of a numeric string; the tests
were read, not rerun for this interview step.

**Reuse assessment:** A service-owned field definition (name plus declared type)
could feed the same normalization/logging mechanism. The existing closed IOC
`LogField` enum is a coupling seam; its product vocabulary should stay local.
The library must not infer units, redact arbitrary values or claim that unrelated
services' field catalogs agree merely because each checks its own types.

**Current behavior to retain as a separate design question:** Mismatched values
throw IllegalArgumentException during event construction, even if that log level
would later be disabled. A usefulness answer does not automatically approve this
failure policy for every future consumer.

**Question asked:** Would a shared mechanism that checks declared log-field types
be useful, with each service owning its own field names and definitions?

**Owner answer:** Confirmed. Each service defines its fields and their types,
then uses the library's tools for the shared processing/logging mechanics.
The product field catalog is not part of the generic library contract.

## 18. Reaction to an invalid structured log value

**Existing evidence:** `LogEvent.field` calls `LogValueNormalizer.normalize`
without containment. A mismatched value throws IllegalArgumentException before
`log()` checks whether the log level is enabled. This exception reaches the
caller; whether it interrupts business work depends on that caller's containment.
The producer code, not the operator's configuration, normally supplies the field
value. This is therefore ordinarily a programming error.

**Decision to discuss:** Should this exception remain visible to calling code,
or should an invalid log field be contained so the surrounding task can continue?
The latter requires an explicitly designed behavior change; it is not already
implemented by this helper. Do not infer either policy from agreement on typed
fields, or implement a new fallback during the interview.

**Owner answer:** Improving containment would be useful, but is unnecessary now.
Record technical debt and retain developer responsibility for the current behavior.

**Decision:** Keep the existing strict throwing contract for the initial library
proposal; service developers own correct field values and exception handling.
The deferred improvement is tracked as
[OBS-6 in the debt registry](../../../KNOWN-ISSUES.md#4-наблюдаемость-obs).
It is not a prerequisite for initial extraction and does not authorize a new
fallback policy or production changes during this interview.

## 19. Explicit URL redaction before logging

**Existing evidence:** `SensitiveLogValueSanitizer.sanitize(String)` is a JDK-only
helper with no IOC type dependency. `LoggingPipelineDecisionTracer` calls it for
item values; `RedactingDiagnosticContextFormatter` uses it on selected text/raw
values allowed by its separate rendering policy. `LogEvent` does not invoke it
automatically. Source and the three existing tests were read for this checkpoint;
no tests were run or changed.

**Actual contract:** Mask URL user-info identified after `://` and mask the whole
query, without URI parsing/normalization; accept partial strings and null. The
remaining host/path and trailing fragment are preserved. This is neither a
general secret detector nor automatic sanitation of log messages/exceptions.

**Proposed reuse boundary:** A deliberately named, explicitly called URL helper;
the service chooses which values require it. Do not infer a new security library,
automatic scanning of all fields, or configurable redaction rules. Its exact
partial-input behavior still needs qualification before any public guarantee.

**Question asked:** Would future services benefit from this explicit helper for
masking URL credentials and query contents before writing selected values to logs?

**Owner answer:** Yes, and the intended need includes tokens, hashes and other
selected sensitive values, not only addresses. Do not restrict the requirement
to URL processing or assume every hash/identifier is sensitive in every service.

## 20. Selected sensitive values beyond URLs

**Additional existing evidence:** `RedactingDiagnosticContextFormatter` selects
the IOC context keys INDICATOR, ITEM and VALUE. At INFO/WARN/ERROR/FATAL it replaces
their text with `[redacted:sha256:<12 hex characters>]`, derived from the first
six SHA-256 digest bytes. It also replaces exact occurrences of those known raw
values in other rendered context values. At DEBUG/TRACE it instead allows raw
values after URL sanitation. This is an IOC diagnostic-rendering policy, not
automatic detection of tokens/hashes or a generic guarantee for every log channel.
The sink passes diagnostic causes separately; context rendering does not sanitize
arbitrary exception text. These source paths were read, not modified.

**Proposed boundary:** The service identifies sensitive fields/values and selects
their rendering policy; shared tools may supply narrowly specified transformations.
Existing URL masking and selected-value fingerprint replacement are extraction
candidates. A universal secret detector and new field annotations are not implied.
The diagnostic key catalog and severity-dependent raw-value allowance remain local;
do not generalize DEBUG/TRACE disclosure as suitable for credentials. Full generic
value masking outside URLs would be a small extension, not an existing public API.

**Next discussion point:** Is correlation of repeated hidden values useful (the
existing fingerprint behavior), or should sensitive fields use a constant mask
without a value-derived identifier? These are different disclosure policies.
An unkeyed truncated hash is not a guarantee of anonymity or safe credential
representation. Record demand before choosing a shared API or extending behavior.

**Owner answer:** A full constant mask is sufficient. Repeated-value correlation
through fingerprints is not required for the shared sensitive-value tool.

**Decision:** The service selects sensitive fields/values; the proposed shared
tool replaces an entire selected value with a constant mask, without retaining
prefixes/suffixes, length or a value-derived fingerprint. The exact marker spelling
is not yet a public contract. This is a small required extension to the extraction
proposal, not a capability already present as a generic public helper. Do not
extract fingerprint generation merely because the current diagnostic formatter
contains it. Existing IOC diagnostic rendering remains unchanged; its migration
must be assessed separately. Keep the URL-component sanitizer as a distinct,
previously confirmed tool: preserving parts of a URL is not full-value masking.
No production changes or automatic detection of secrets are authorized here.

## 21. LIB-3 design checkpoint

The owner requested code analysis and a plan-proposal using system-design,
java-code-review, multi-module-maven, architecture-review and SOLID guidance.
The [logging library proposal](lib-3-logging-design.md) records the completed
review and stages L3-0 through L3-4.

Recommendation: one SLF4J API-dependent JAR for scope restoration, typed fields
and explicit redaction helpers; IOC taxonomy, renderers, pipeline observers and
runtime backend configuration stay local. SLF4J already supplies fluent builders
and dispatch. The proposed added value is declared type checking, restoration of
previous MDC values and event/MDC collision handling, not another logging engine.

The design resolves the mask/type seam without weakening field types: a textual
mask needs a STRING log representation. It also records duplicate descriptor
identity, null omission versus ambient hiding, same-thread/LIFO scope ownership,
resource acquisition and provider-output limits. Full masking is the requested
small extension; existing diagnostic fingerprints remain service policy. OBS-6
remains deferred and does not block extraction.

Existing observability and diagnostics-logging module tests passed: 22 and 37
local tests respectively, no failures/errors/skips. This is existing-behavior
evidence, not qualification of the proposed API or publication. Production/build
code is unchanged. Diagnostics, configuration and logging now all have design
proposals; no automatic move to implementation is implied.

## 22. LIB-4 ETL exploration checkpoint

The owner agreed to review `platform-etl`, while questioning whether its actual
instruments would be useful beyond IOC extraction. Existing data processing is
not sufficient evidence of a second consumer. The [assessment](lib-4-etl-analysis.md)
records source/test/dependency review on `edca8d69` and the current alternatives.

The shared mechanism is sequential synchronous stage execution with diagnostics,
stop policy and observation. Payload transformations, transactions, remote work,
retry/recovery and scheduling remain service-owned. Managed import already
reuses processing rules without this runner. The existing application and its
logging observer are one business flow, not two independent consumers.

Review reproduced three external-contract weaknesses: public construction can
bypass the typed chain, bounded reporting can alter a custom policy decision,
and the append-only guard can accept/discard a severity replacement because of
current diagnostic equality. Initial-diagnostic timing and scope/callback failure
semantics also need explicit contracts. Current fluent IOC wiring/built-in
policies do not demonstrate the custom-consumer failures as production incidents.

Recommendation: retain **deferred-second-consumer**; no standalone-library plan
or implementation yet. The existing LIB-2 equality/exception/suppression choices
must be reconciled before ETL could be published. No new framework is proposed.

Focused tests passed: 16 ETL and 34 upstream diagnostics, no failures/errors/skips.
Five temporary probe observations reproduced the public-contract limits; they
are not publication admission. No production/build changes or full verify/PMD.
Documentation validation: `make docs` and `git diff --check` passed; all relative
file links in the new assessment resolve.

**Next question (not answered):** Would another multi-stage operation benefit
from common execution/reporting conventions while the service defines all
transformations, or should this orchestration remain local until a concrete
second flow appears?

**Owner answer:** No corresponding multi-stage operation is currently expected
in adjacent services. The owner agrees to defer ETL extraction. Reusable data
processing tools would be useful where existing code adds specific capabilities
beyond established public libraries.

**Decision:** Close this ETL exploration with `deferred-second-consumer`; keep
the module internal and preserve the recorded findings for a future concrete
consumer. The suggested next screening area is existing indicator-text cleanup,
ordered refanging and network-value feature extraction. Assess their added value
against standard/established tools before proposing any library; no general
`data-utils` artifact or new processing functionality is implied. Conduct that
separate analysis in its own task branch.

**Owner response to the suggested next area:** Do not pursue a separate library
for text cleanup/refanging/network-value parsing. The owner sees no added reuse
value over the Java libraries already used for these operations.

**Disposition:** Drop this proposed screening task. No independent comparison
or extraction analysis was performed, and this decision does not assert that
every current helper is implemented by a third-party library. Do not create a
new branch, utility artifact or replacement implementation for this candidate.
The next recommendation is to consolidate the existing diagnostics,
configuration and logging proposals and choose an implementation priority;
that choice and implementation remain pending owner direction.

## 23. Evidence-based correction of the data-tools recommendation

The owner challenged the assistant's unverified agreement and requested an
analysis before accepting or rejecting a candidate. This section supersedes the
previous screening disposition's rationale. ETL remains deferred; this is a
bounded correction to the candidate discussion, not an extraction design.

**Live code inspection on edca8d69:**

| Capability | Actual implementation | Added semantics / extraction assessment |
|---|---|---|
| Public suffix classification | `PslHostClassifier` delegates to Guava `InternetDomainName` and maps results to local `HostKind` | Mature external mechanism with local policy; no new PSL engine to extract |
| Text-edge cleanup | `DefaultIndicatorNormalizer` manually trims a fixed character set | Small standard operation plus product-selected punctuation; weak standalone value |
| Refanging | `ReplacementRefanger` loops over configured literal rules using JDK `String.replace`; returns per-rule occurrence counts | Ordered transformations and an explicit report are existing added semantics, though their cross-service demand is unconfirmed |
| Address feature extraction | `DefaultIndicatorFeatureExtractor` uses custom string scanning, then calls `HostClassifier` | Handles the product's scheme-less host/path/query inputs; not a general URI parser and currently coupled to `Indicator`/`IndicatorFeatures` |

Sources: `core/ioc-domain/src/main/java/com/iocextractor/domain/{refang,feature}`,
`adapters/adapter-psl/src/main/java/com/iocextractor/adapter/out/psl/PslHostClassifier.java`,
`bootstrap/.../AppConfig.java:227-243`, and the refanger/feature-extractor tests.
The claim that ready-made libraries already implement all these behaviors is
incorrect. The opposite claim that these behaviors are absent from public Java
libraries is also unproven; no exhaustive ecosystem survey was performed.

**Primary-source comparison, checked 2026-09-15:**

- [Guava InternetDomainName](https://guava.dev/releases/33.4.0-jre/api/docs/com/google/common/net/InternetDomainName.html)
  provides public-suffix/domain operations used by the adapter. This is API
  reference evidence, not a recommendation to change the resolved version.
- [Apache Commons Lang StringUtils](https://commons.apache.org/proper/commons-lang/apidocs/org/apache/commons/lang3/StringUtils.html)
  already supports stripping a supplied character set and multiple replacements.
  `replaceEach` is not an exact substitute for our sequential rule loop: its
  documented example does not reprocess replacement output, whereas our next
  rule receives the previous rule's output. It does not return our per-rule
  report. This establishes a contract distinction, not a novel algorithm or a
  reason to add Commons Lang to the framework-free domain.
- [JDK URI](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URI.html)
  provides URI parsing/normalization. Bare host-like strings can be relative URI
  paths; our feature parser assigns domain-specific host/path meanings. Replacing
  it requires an input-contract comparison, not a mechanical method swap. Its
  current custom implementation must not be advertised as a complete URI parser.

**Practice assessment:** A reusable layer over existing libraries is legitimate
when it owns meaningful shared conventions, adaptation or maintenance policy.
[Microservice Chassis](https://microservices.io/patterns/microservice-chassis.html)
explicitly composes existing frameworks for cross-cutting behavior. This supports
shared integration as a category; it does not justify a broad chassis artifact
for this project. [Anti-Corruption Layer](https://learn.microsoft.com/en-us/azure/architecture/patterns/anti-corruption-layer)
also explains the value of adapting external semantics, and allows a local
component. An adapter boundary is not automatically a publication boundary.

[YAGNI](https://martinfowler.com/bliki/Yagni.html) argues against paying for
speculative capabilities before they are needed. Applied here, the additional
capability is a supported external API/release lifecycle, even if the underlying
implementation already exists. Existing modular code can remain easy to extract
later without publishing it today.

**Revised judgment:** Keep these helpers local for now because the reviewed
subset has limited demonstrated shared value and no confirmed consumer for its
specific contracts, not because it is all third-party code or inherently useless.
Refanging with ordered rules and reporting is the most plausible narrowly scoped
candidate if real demand appears. Novel algorithms are not a prerequisite for a
useful library; consistent behavior can itself be the product. Conversely,
several wrappers alone do not establish a worthwhile independent artifact.

For all candidates, distinguish (1) behavior provided by existing dependencies,
(2) our added contract, (3) who needs that exact contract, and (4) publication and
compatibility cost. The diagnostics/config/logging proposals must meet the same
criterion; their approved design direction is not proof of completed admission.
No automatic move to diagnostics implementation follows from this correction.

Validation: source/test inspection and primary documentation comparison only;
no runtime claims, dependency substitutions or production changes. Tests and
full verify/PMD were not rerun for this discussion correction.
