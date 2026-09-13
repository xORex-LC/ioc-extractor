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
