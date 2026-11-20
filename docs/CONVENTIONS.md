# Conventions

[Русская версия](ru/CONVENTIONS.md)

These requirements apply to code and documentation throughout the repository.
Use the same contract vocabulary and Javadoc style across modules. Choose the
amount of explanation by responsibility, risk and what a reader needs to understand;
line counts and comment counts are not quality targets.

## Documentation ownership

| Document | Responsibility |
|---|---|
| Root maps and policies | Project skeleton, structure, main responsibilities, boundaries and project-wide rules |
| Capability docs in `docs/dev/` | One capability's flow, invariants, failures, recovery and extension points |
| ADR | Decision context, alternatives and consequences; append-only history |
| Operator/integrator guides | Supported setup, usage, operation and migration |
| Module/directory README | Local responsibility, structure, dependencies, entry points and key contracts |
| Javadoc / `package-info.java` | Type, operation and package guarantees needed to use or change the code |
| Generated references | Facts derived from the owning catalogs/generators |

Give detailed facts an owner and link to it. A short structural overview, dependency
diagram or key-port map can remain in a root document when it helps orientation.
Avoid maintaining complete competing class, configuration or version inventories.

Audit completeness within the selected documentation scope. A root-map update
does not require rewriting every linked README; a module review checks that its
README actually supplies the promised local detail. A link is navigation, not proof
that the destination has passed a semantic review.

## Javadoc

### Required coverage

Public/protected API types and operations, including inward-facing ports, need a
meaningful contract. Document non-obvious behavior in internal types and helpers
as well: complex models, invariants, algorithms, representations, lifecycle and
failure-sensitive transitions must remain understandable to their maintainer.

Use type-level descriptions and record-component documentation to cover obvious
generated accessors. An override can inherit a complete unchanged contract.
Add the implementation's extra guarantees or limitations where they matter.
Routine private helpers, trivial accessors and each individual line do not need
repeated narration. Short Javadoc is sufficient when it states the whole useful
contract; complex behavior requires enough explanation to remove ambiguity.
Put shared package guarantees and vocabulary in `package-info.java`; keep the
package README as the structural reading map.

### What to explain

Start with what the type represents or what the operation guarantees. Then explain
the facts that affect correct use or safe modification:

| Concern | Relevant information |
|---|---|
| Role and boundary | Responsibility, collaborators and which decisions belong elsewhere |
| Model and representation | Meaning of fields/components, units, valid ranges, null/empty/absent distinctions, identity, equality and ordering |
| Invariants | Conditions preserved across construction and transitions, including boundary equality and consistency between related values |
| Ownership and lifecycle | Copies versus borrowed/live views, shallow versus deep immutability, who closes resources, valid use after close and cleanup ordering |
| Concurrency | Scope of exclusion/order, thread affinity, reentrancy, callback execution and cancellation/interrupt behavior |
| Effects and failures | I/O or durable effects, atomicity scope, partial outcomes, retry/idempotency conditions, exceptions and state retained after failure |
| Non-obvious reasoning | Why an ordering, representation or algorithmic constraint is necessary; what would break if it changed |
| Complexity and bounds | Time/memory cost, retained state and capacity limits when they affect supported use |

Apply the relevant concerns, rather than adding every heading to every member.
For an internal algorithm, a short local comment can explain a surprising step;
Javadoc should explain the enduring type/operation contract. Describe an
implementation detail when it is needed to understand an invariant or operational
limit, and distinguish it from a public guarantee.

Use precise scopes. For example, per-key exclusion within one executor instance
does not imply cross-process fencing; an immutable collection does not imply that
its elements are deeply immutable. Explain guarantees and limitations together.
Document predictable boundary failures and their conditions; avoid cataloging
every incidental exception a called JDK method could throw.

### Writing style and layout

- Write Javadoc in English, using direct, concrete sentences. Prefer
  “Returns exact counts, including omitted occurrences” to “Provides count
  processing functionality.” Explain behavior rather than repeating a type name.
- Put the purpose or observable guarantee in the first sentence. Follow with short
  paragraphs for invariants and applicable ownership, concurrency and failure
  behavior. Use the same order for comparable contracts across modules.
- Use normal Javadoc markup: `{@code ...}` for identifiers, values and formulas;
  `{@link ...}` for resolvable types/members; `<p>` for paragraphs and concise
  lists for genuinely parallel facts. Use `<pre>{@code ...}</pre>` for examples.
- Longer type contracts may use headings such as “Invariants”, “Ownership and
  lifecycle”, “Thread safety” and “Failure behavior”. Include only useful sections;
  empty templates and fixed paragraph lengths are not requirements.
- Keep block tags after the description: type parameters and parameters in
  declaration order, then return value, then exceptions. Describe meaning, units,
  bounds, ownership and nullability where relevant. `@param rows the rows` and
  `/** Gets name. */` add no contract.
- Use `@throws` for the exception and the triggering condition. For a result-based
  API, explain success/partial/rejected outcomes in the result/return contract.
  State whether an error can follow a successful durable effect.
- Use record `@param` tags for meaningful component contracts. Inherit existing
  documentation with `{@inheritDoc}` where appropriate; describe changed behavior
  explicitly instead of copying a contract that no longer applies.
- Add a small input/output, boundary or usage example when it resolves a likely
  misunderstanding. Keep examples synthetic, executable in their stated context
  and aligned with the API version. Do not embed secrets or real IOC data.
- Keep implementation rationale next to the relevant code. Significant historical
  decisions belong in ADRs; Javadoc links to related contracts rather than copying
  a complete capability guide or configuration reference.

### Example and style references

This excerpt from
[DiagnosticBatch.of](../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/result/DiagnosticBatch.java)
states ordering, copy/null behavior, exact-count semantics and validation failures.
The method belongs inside the linked class, which supplies its imports and types.

```java
/**
 * Validates retained counts, severity counts and rejecting signals.
 *
 * @param retained occurrences in caller-supplied order; copied, with no null elements
 * @param summary exact counts, including omitted occurrences
 * @param suppressionCode reserved synthetic marker shared by the aggregation tree
 * @return an immutable batch
 * @throws NullPointerException if an argument or retained element is null
 * @throws IllegalArgumentException if metadata or counts are inconsistent, a marker
 *         is retained, or an observed ERROR/FATAL has no retained rejecting signal
 */
public static DiagnosticBatch of(List<Diagnostic> retained, DiagnosticSummary summary,
                                 DiagnosticCode suppressionCode) {
    return new DiagnosticBatch(retained, summary, suppressionCode);
}
```

Other useful references illustrate different responsibilities:

- [KeyedSerialExecutor](../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/KeyedSerialExecutor.java):
  admission order, repeated submissions and the boundary between in-memory work
  and durable business handling.
- [MdcScope](../platform/platform-logging-slf4j/src/main/java/com/iocextractor/platform/logging/MdcScope.java):
  same-thread ownership, nested close order, restoration and provider failures.
- [PropertyInspection](../adapters/adapter-config-spring-boot/src/main/java/com/iocextractor/config/boot/PropertyInspection.java):
  ordered observations, coverage, sensitive names and separation from service policy.

Follow their specificity and explanation of consequences. Reuse the applicable
style, not an identical set of paragraphs regardless of the component.

### Maintenance and review

With a behavior/API change, review the affected type, constructors, operations,
model components and relevant package documentation. Update invariants, failure
conditions, lifecycle and examples in the same change. Inspect meaningful internal
helpers too; public-only documentation does not explain every important mechanism.

Before considering the change complete, check that a reader can determine:

1. What the component represents or guarantees and where its responsibility ends.
2. Which inputs/states are valid and which boundary cases have distinct meaning.
3. What it owns, changes or retains, including behavior on failure and concurrency.
4. Which guarantees a caller may rely on and why the surprising constraints exist.

Resolve links, compile/run examples in their documented context and use applicable
Javadoc generation/doclint checks. These checks validate syntax/references; they
do not establish factual completeness, clarity or correctness of guarantees.
Publication profiles select their own doclint settings (`all` or `all,-missing`).
Review the applicable profile; this policy does not silently change build settings.

## Languages and translations

Canonical published documents and substantive rewrites are English. Required
Russian translations use these locations, preserving nested paths below a bucket:

| English canonical | Russian translation |
|---|---|
| `docs/*.md` | `docs/ru/*.md` |
| `docs/dev/*.md` | `docs/dev/ru/*.md` |
| `docs/ADR/*.md` | `docs/ADR/ru/*.md` |
| `docs/guides/*.md` | `docs/guides/ru/*.md` |

Keep each document's prose in one language. Code, comments and Javadoc are English.
Root/module/directory/package README files are English-only and translated in
place. Keep existing Russian navigation useful when reconciling legacy README
locations; ordinary guides retain their translation pairs. Worknotes remain
Russian planning/evidence without translation pairs.

Update both language versions when their documented contract changes. Runnable Java/XML/YAML
blocks must stay identical; code comments remain English. Check semantic parity
as well as links and block equality. Distinguish current source additions from
the API available in an existing published artifact.

Accepted ADRs remain append-only. Controlled translation migration preserves a
Russian historical original byte-for-byte under `docs/ADR/ru/`; its canonical
English translation is explicitly labelled, retains the decision/status/number
and links to that original. The ADR index maps the versions. Plan relative-link
handling before moving originals; review semantic parity without rewriting history.

Generated references change through their owning generator. Diagnostic and logging
catalogs are excluded from manual translation. Release execution bundles are
planning/evidence, not published architecture authority or link targets from
published docs/README files.

<a id="readme-в-каждом-каталоге"></a>

## Module and directory README

Every significant module/directory has a current README. It describes the local
responsibility, its boundary, structure, dependencies and useful starting points.
Add ownership/lifecycle, key ports, failure constraints and verification references
when they are needed to change that component safely. Precise member contracts
remain in Javadoc.

Use a compact structure such as:

```markdown
# <module-or-directory>

## Purpose and boundary

State the responsibility, layer rules and what belongs elsewhere.

## Structure

| Directory / file | Responsibility |
|---|---|
| component/ | Cohesive local capability |

## Dependencies

Name allowed dependencies and important forbidden outward coupling.

## Entry points and contracts

Identify the main ports, owners and lifecycle constraints.

## Verification and related docs

Point to relevant tests, capability docs and public API documentation.
```

Keep purpose, structure and dependency rules explicit for modules. Leaf-package
READMEs may use a shorter purpose/file map and link to parent rules. A README is
not required to list every class or duplicate the POM; include the local detail
that its parent map delegates to it. Audit that detail when the module is in scope.

## Capability docs and library guides

Create capability guides from [dev/template.md](dev/template.md). Explain one
capability's flow, boundary, invariants, failures/recovery and extension path.
Include “Sources of truth” and “When to update this document” with concrete source,
configuration and test references. A class rename requires link repair, not a
rewrite of unchanged behavior.

Library guides additionally cover dependencies, working examples, guarantees,
errors and resource ownership. The [catalog](guides/libraries/README.md) is the
entry point. Before introducing another mechanism, inspect and reuse or extend a
suitable library; keep service policy with its owner. Follow the
[development guide](guides/libraries/development.md) and
[release policy](RELEASE-PROCESS.md#reusable-library-versions).
Exercise actual guide examples against packaged APIs. Promote durable results
before retiring completed scratch notes and repairing incoming links; preserve
the allowlisted release execution bundle and open evidence.

## Code and test conventions

- Prefer immutable values and records where appropriate. Copy owned collections
  and state whether nested values are shared. Use constructor injection.
- Keep ports focused and adapters behind their contracts. Framework composition
  belongs in bootstrap; exact layer rules belong to [BOUNDARIES.md](BOUNDARIES.md).
- Keep source/sink/rule-specific declarations in validated configuration.
  Regex patterns remain RE2-compatible; avoid look-around and back-references.
- Keep a clearer method reference instead of rewriting it solely for Eclipse JDT
  `nullUncheckedConversion`. The shared
  [JDT preferences](../tools/eclipse-jdt.prefs) retain real/potential null diagnostics.
- Tests own synthetic inputs and temporary outputs. Unit/domain tests do not
  depend on operator data or production configuration. Explicit contract tests
  may read production resources to verify those boundaries and must make that
  purpose clear. Keep minimal fixtures in `src/test/resources` or `@TempDir`;
  write outputs to temporary directories, never runtime artifact directories.
- Assert supported behavior, including meaningful failures and recovery.
  Use JUnit Jupiter and AssertJ; parameterized case tables suit independent examples.
  [TESTING.md](TESTING.md) owns lifecycle, tags, coverage, waits and flake policy.

Update affected documentation with a feature, refactor, retirement or contract
change. Check scope and applicable tests/generators, EN/RU parity and local links.
`DocumentationConventionTest` currently checks module README presence;
`make docs` checks local links. Neither proves complete content or this entire
style policy.
