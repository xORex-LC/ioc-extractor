# C0: independent configuration contract qualification

Date: 2026-09-13. Baseline: `release-0.3.0`,
`0df7183fcc02cfd010104733c3608e710b059be9`.
Scope: C0 of the [configuration library proposal](configuration-library-design.md).

## Outcome

**The C0 analysis is complete. Direct extraction of the existing classes is not
qualified. Proceeding to C1 is reasonable with the contract corrections below.**
This is a design recommendation, not implementation or publication admission.

The useful mechanism works with an unrelated record schema and prefix. However,
54 characterization scenarios demonstrate that its current name inspection is
not equivalent to Boot binding, and its reporter does not identify all effective
bound-value sources. The discrepancies are reproducible, not only code-review
suspicions. They do not justify building a new configuration framework.

No second service exists. The fixture is deliberately artificial and establishes
technical independence of the inspected algorithms; it does not establish a
second production consumer or the stability of a published library API.

## Method and reproducibility

The [probe](c0-probe/README.md) uses Java 21.0.11, Spring Boot 4.0.8 and Spring
Framework 7.0.9 from the project's resolved dependencies. It compiles five current
source files into a temporary directory. Only these seams are substituted:

- `ioc` / `ioc.` constants become `collector` / `collector.`;
- references to `IocProperties.class` become the synthetic schema type.

The matching, enumeration, canonicalization, exception and reporting algorithms
are unchanged. The migration catalog is compiled unchanged solely to satisfy
the reporter's linkage; migration logging is not invoked. No reactor class
directories or IOC JARs are on the probe classpath. No production files, Maven
modules, dependency declarations or suite-discovery rules are modified.

For each input, the probe runs name preflight and Boot Binder **separately**.
Binder deliberately still runs when preflight rejects the input, allowing false
rejections to be observed. Binder uses its normal lenient unknown-field behavior
and a conversion service; a configured synthetic Token converter is used in the
two converted-record cases. Binding does not run service semantic validation.

Therefore `ACCEPT` means only that the name preflight did not reject the source.
It does not mean a valid value, successful binding or permission to start a
service. `UNBOUND` does not mean a successful binding of the supplied parameter.

Full reviewed observations: [expected.tsv](c0-probe/expected.tsv).
Source/dependency hashes: [baseline.json](c0-probe/baseline.json).
The rerunnable probe compares actual output with that reviewed characterization.
Known defects in this snapshot are observations, not desired future behavior.

The primary fixture contains ordinary settings:

```yaml
collector:
  enabled: true
  request-timeout: 30s
  connections:
    - name: alpha
      request-timeout: 30s
  tags: [a, b]
  labels:
    region: east
```

It has no feeds, IOC, storage, endpoint-lifecycle or future-service business rules.
Additional tiny schemas isolate aliases, collection types, ambiguity and custom
conversion. Each environment is cleared of real system/environment sources;
all emitted values and labels are synthetic. No remote service is contacted.

## Observed compatibility matrix

Rows group related experiments; the TSV provides every input case's full result.

| Cases | Name preflight | Actual Boot binding | Meaning |
|---|---|---|---|
| Canonical kebab names; nested records; CLI; parsed YAML | Accepts | Expected fields bind | Reusable baseline established |
| `requestTimeout`, `requesttimeout` | Unknown | `requestTimeout=PT30S` | False rejections of relaxed spellings |
| `request-timout` typo alongside a known field | Unknown | Known field binds; typo does not | Useful name check; lenient Binder alone does not reject the typo |
| Invalid duration value | Accepts | Conversion fails | Correct separation of name checking and value validation |
| Record supplied as an unconverted scalar | Accepts | Conversion fails | A recognized path is not a valid scalar representation |
| Indexed record list; indexed/whole scalar list | Accepts | Expected elements bind | Reusable baseline established |
| Scalar list starts at index 1 | Accepts | Unbound-elements failure | Index continuity belongs to binding, not just name recognition |
| Negative index; trailing text after index | Rejects | Binding fails | Public canonicalization rejects these cases; lexical helper concerns alone did not prove acceptance |
| Flat string map; enum-keyed scalar map | Accepts | Expected map binds | Finite schema plus dynamic final key works |
| Invalid enum map key | Accepts | Conversion fails | Map key conversion remains Boot-owned |
| Dotted/bracketed dotted string-map key | Unknown | `zone.name` key binds | Dynamic-key grammar is broader than the local tokenizer |
| Simple env name; split multiword env name | Accepts | Expected fields bind | Existing IOC-style split spelling works in this fixture |
| Compact env name, including inside an indexed list | Unknown | Expected fields bind | Same mismatch applies to normal record/list use |
| Whole scalar list in an env variable | Unknown | `[a, b]` binds | Environment/container termination mismatch |
| Env map tail | Accepts | Expected map binds | Baseline dynamic tail works |
| Bare env root `COLLECTOR` | Ignores | Root conversion fails | Prefix exclusion is not validation of a root scalar |
| An unrelated prefix | Ignores | Collector is unbound | Scope isolation works |
| `@Name("remote-name")` | Alias unknown; Java spelling accepted | Alias binds; Java spelling alone is unbound | Reflection component names do not describe alias semantics |
| Nested map value; nested JavaBean; indexed Set/array | Unknown | Expected values bind | Explicit unsupported-schema cases |
| Acronym component `URLValue` | `url-value` unknown | Expected value binds | Custom per-capital kebab conversion differs from Binder |
| Record with String-to-record converter | Scalar accepted; env scalar unknown | Both bind through the converter | Reflection alone cannot infer scalar-converted record semantics |
| Ambiguous `fooBar` versus `foo.bar` env segmentation | Throws ambiguity | Both fields receive the value | Ambiguity is a real schema fact; rejection remains service policy |
| Unknown env name followed by ambiguity | Throws ambiguity | Known ambiguous paths bind | Earlier unknown findings are not returned as one collected result |

### Property-source identity matters

For the same `SystemEnvironmentPropertySource` payload, named `systemEnvironment`
or `probe-systemEnvironment`, compact environment spelling binds. With name
`custom-env`, that compact spelling is unbound in this direct Binder experiment.
The existing checker dispatches solely on the Java source type. This means a
generic adapter must align source adaptation with Boot, not claim every object
of that type has identical binding semantics. Custom source conventions remain
outside initial support until qualified explicitly.

Supplying both canonical and camel spellings in one map also demonstrates the
reporter's limitation: Binder chooses the canonical supplied value in this
fixture, while the reporter emits two differently canonicalized keys and the
preflight rejects one. A library should preserve declarations without claiming
that each declaration supplied a distinct bound field.

## Source reporting and coverage findings

| Experiment | Observed result | Required interpretation |
|---|---|---|
| Higher source defines only `connections[0].name`; lower defines its timeout and element 1 | Bound list has one element with the new name and null timeout; reporter also lists lower timeout and element 1 | Neither lower field survived binding; report is not effective provenance |
| Higher whole scalar list over lower indexed list | Bound list is `[new]`; report also lists lower indexes | Declared names and final aggregate values are different contracts |
| Higher baseline source over lower external source | Bound value comes from baseline; report names external source | Excluding baseline before winner selection changes the reported answer |
| Null entry in higher source | Binder uses lower value; report names higher source | Enumeration alone cannot establish value contribution |
| Higher nonenumerable source | Binder resolves its value; checker/report silently omit that source | Inspection must disclose incomplete coverage |
| Enumerable composite of enumerable sources | Nested names are checked and reported at composite level | Standard composite enumeration works; earlier blanket composite concerns were too broad |
| Composite including a nonenumerable source | Preflight, reporter and this Binder path throw `IllegalStateException` | Cannot advertise arbitrary composite support or mislabel this as env ambiguity |
| Unknown declaration in a lower source | Unknown is still rejected | Current preflight examines declarations, not only effective winners |
| Synthetic secret in source name | Raw label appears in report | Value-free property scanning does not sanitize source labels |
| Source whose value getter throws AssertionError | Name preflight and reporter finish without reading values | Direct evidence of value-free name enumeration |
| Mutate environment match's returned set | Previously known result becomes unknown | Public result must use defensive copies |

The list result is stronger than merely losing sibling fields: in this fixture
the lower list tail disappears too. The wording in
[configuration.md](../../../dev/configuration.md#неочевидные-инварианты) must not
be generalized into a promise that indexes merge across sources. This experiment
uses direct Binder and a synthetic schema, not the full IOC startup context.
Before changing published IOC-specific wording, add a corresponding real IOC
startup fixture in C2. No accepted ADR is rewritten by this analysis.

## Proposed v1 support boundary after C0

These are requirements for C1, not claims that the current implementation already
meets them. Keep one Boot integration module and service-owned enforcement.

| Area | Proposed initial contract |
|---|---|
| Root | Explicit single canonical prefix and ordinary Java record schema |
| Structure | Nested records, resolvable Lists of supported values and flat scalar maps; ordinary scalar types are converted by Boot |
| Name equivalence | Recognize canonical/camel/dashless field spellings and qualified compact/split env spellings within the supported schema; report ambiguity instead of guessing |
| Container declarations | Recognize tested scalar-list declarations consistently across supported channels; Binder owns representation and index/value validity |
| Dynamic keys | Initially support the qualified single-token map tail; other key grammars produce unsupported/incomplete findings, not false unknown-property certainty |
| Unsupported schema | Detect `@Name`, acronym naming not yet modeled, nested JavaBeans/maps, Sets/arrays, unresolved generics and custom scalar-record semantics; refuse complete-inspection claims for those subtrees |
| Custom conversions | Continue using service/Boot converters; do not invent a second conversion registry. Scalar-converted records need explicit qualification before shared support |
| Sources | Prepared, stable, ordered sources with explicit coverage; align env adaptation with Boot's qualified source conventions |
| Observations | Canonical candidates, raw declaration name and safe source reference; preserve source order; no final bound-value provenance promise |
| Findings | Unknown, ambiguous and unsupported/incomplete are distinct; expected input problems collect as facts; operational enumeration failures are not unknown names |
| Output | No property values or arbitrary raw source names in shared result labels; parameter names themselves are not universally safe text |
| Ownership | Boot binds/converts; each service validates meanings, selects severity, renders and enforces |

This does not expand the intended product feature set. It makes the boundary
honest for settings forms already under review. Unqualified shapes can remain
unsupported in v1; the library must say so. A complete generic model of all Boot
binding is unnecessary.

### Required corrections before accepting C1

1. Use Boot-aware name equivalence for the qualified schema instead of literal
   kebab token comparison alone. Preserve ambiguous candidates. Show the IOC
   compatibility delta explicitly: correcting generic recognition must not
   silently change its established strict policy. Any retained IOC spelling
   restriction belongs in the service adapter, not in a universal library rule.
2. Add explicit schema/source coverage and separate expected findings from
   enumeration/programming failures. Unsupported forms must not be called known
   or unknown with false certainty. Preserve all available findings, even when
   one environment name is ambiguous.
3. Make source observation the shared API; keep IOC's existing external-source
   display selection in the adapter during migration. Do not implement a new
   effective-provenance engine or infer aggregate binding from leaf enumeration.
4. Copy result collections defensively and use safe source classifications.
   Preserve the no-value-read behavior demonstrated by the trap-source case.
5. Convert selected probe cases into normal module/consumer contract tests when
   the actual API exists, including positive, unknown, ambiguity and unsupported
   outcomes. This probe's known-defect snapshot is not the future acceptance suite.

The original C0 slice also mentions a named capability/release owner. This
analysis does not assign a person or claim publication admission. Record that
owner in the admission work before C4; it does not prevent completing the
technical experiment requested here.

## Evidence limits and next action

The isolated probe reproduced 54 reviewed scenarios. The existing
`IocConfigurationOverrideReporterTest` was also rerun through the facade: four
tests passed without failures, errors or skips. That refresh supplied the resolved
classpath, while the probe itself used only selected third-party JARs.

This did not exercise a new public API, full application startup, profile/import
activation, semantic validators, reload, actual secrets, standalone publication
or repository routing. It is not a new Maven suite or release-quality gate.
No production/build/analyzer scope changed; full `make verify` and
`make pmd-analysis` were not run, and their older freshness remains unchanged.
No claim is made about new analyzer findings from checks not run.

Recommendation: use these corrections as C1 acceptance requirements. The useful
boundary survives C0, but a package move alone would publish misleading behavior.
Implementation and any deliberate IOC behavior corrections remain the next task.
