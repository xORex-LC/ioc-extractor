> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0016-config-preflight-strict-binding.md) (relative link addresses rebased on relocation).

<a id="0016--надёжность-конфигурации-единый-preflight--строгий-binding"></a>
# 0016 — Configuration reliability: unified preflight + strict binding

<a id="статус"></a>
## Status

**Accepted 2026-07-08, not implemented.** Addresses the `CFG` block in
[KNOWN-ISSUES](../KNOWN-ISSUES.md) (CFG-1, CFG-2, CFG-4; CFG-3 already closed
by code removal during β-collapse) as one problem class, not isolated fixes.
The issue inventory, code verification, binding-pipeline mechanics and
implementation order were also initially kept in a local scratch note outside
published documentation. This ADR records the durable contract;
[KNOWN-ISSUES.md](../KNOWN-ISSUES.md) records final debt status.

**Implemented 2026-07-09** in commit range `b0e5157..8575527`
(`b0e5157`, `9bb9e63`, `ec14c8d`, `da27b24`, `30f1884`, `3d45cdd`,
`e01e2fa`, `8575527`). Shipped model: `configurationPropertiesValidator` for
semantic collect-all, reflection-shape unknown-key preflight instead of
`ignoreUnknownFields=false`, legacy `FailureAnalyzer` with `CONFIG.*` codes,
typed selectors, sealed `IdStart` + converter, registry-backed eager preflight.
CFG-1, CFG-2, CFG-3 and CFG-4 are closed in [KNOWN-ISSUES](../KNOWN-ISSUES.md).

**Addendum 2026-07-10.** CFG-4 reopened after discovery of a silent environment
channel: `IocUnknownConfigurationPreflight` deliberately skipped every environment
key except legacy tombstones, so a typo such as `IOC_PIPELINE_DEDUPLICAT` left
the default without startup failure. Two Boot-native spikes produced no safe
solution: adapted environment-source iteration loses kebab-case boundaries,
and resolve probes give no stable raw name for “raw environment minus explained
names”. Therefore, **only** for `SystemEnvironmentPropertySource`, a narrow
schema-aware join matcher `IocEnvironmentPropertyMatcher` (`5851ec1`) was admitted:
it matches environment tokens against the declared `IocProperties` reflection
shape, joining only into known kebab-case record components; list indexes and
single-token map tails retain ordinary shape rules. Dot channels keep exact
shape matching. Ambiguous segmentation into different canonical keys stops startup.

`IOC_*` is now reserved for `ioc.*`: a variable the matcher assigns to this tree
must be known; unrelated `IOC_*` on bare metal is a deployment error. `PATH`,
bare `IOC` and names outside `ioc.*` are unaffected. Unknown-environment failures
show the original `IOC_…` name and canonical `ioc.*` key, preserving targeted
legacy migration hints. After successful startup,
`IocConfigurationOverrideReporter` (`2c224c2`) logs winning external overrides
once as `key <- source`, without values. Precedence stays ordinary Spring Boot:
`CLI > system properties > env > config files`; packaged classpath defaults
are not overrides.

**Clarification 2026-07-10.** The reporter listens to `ApplicationStartedEvent`:
the context has refreshed, but `ApplicationRunner` has not started useful work.
Provenance is therefore visible even before a oneshot command that later fails;
this refines “successful startup” above.

<a id="контекст"></a>
## Context

`ioc.*` is not merely “settings”: it is a DSL with typed values and symbolic
references between sections (`artifact-identity → sink`, `export → sink`,
`classify → predicate registry`, `columns.from → provider registry`). Validation
is spread over three idioms (JSR-380 annotations, compact-constructor throws,
registry throws during bean creation) and occurs at four different moments:
binding, context startup, first write — or never.

The worst failure mode is CFG-2: an `artifact-identity` typo is not exposed at
startup. A reference to a nonexistent artifact is a silent no-op plus a garbage
durable identity-store entry; an artifact lacking an identity definition fails
on first write, not startup (“Cannot resolve row_key”; in daemon mode the file
retries/fails without a configuration hint). A typo in one composite `key-columns`
does not fail at all: the column silently encodes `null`, a wrong `row_key` is
stored permanently, and correcting the typo means identity drift → HALT → epoch
migration. A configuration error appears as a data error, at a different time
and place.

Unknown-key binding is relaxed: a misspelled key silently leaves the default.
Two handwritten tombstones have accumulated for retired keys (`smb.read-timeout`,
the entire `Lookup` record). They work, but every retirement needs a new manual
guard, leaving ghost fields in the model forever.

<a id="решения"></a>
## Decisions

<a id="1-единый-startup-preflight-для-ioc"></a>
### 1. Unified startup preflight for `ioc.*`

`ioc.*` passes one operator-facing startup preflight before runtime-graph
construction. The target report covers unknown/deprecated keys, binding/conversion
failures, JSR-380, local invariants, closed vocabularies and config→config
referential integrity. No `ioc.*` error survives until first write, daemon file
handling or lazy export/sync-graph activation.

Semantic preflight lives in an `org.springframework.validation.Validator` bean
named `configurationPropertiesValidator` (static `@Bean`), the standard Spring
Boot hook executed during binding by the same `ValidationBindHandler` as JSR-380.
It accumulates violations on the already-bound shape with property paths
(`ioc.artifact-identity.artifacts[2].key-columns[1]`), rather than throw-first.

Technical limitation: malformed YAML, conversion, enum-binding and unknown-key
failures can stop binding before some semantic checks because no configuration
object exists. These are shape/binding failures. Every error on a constructed
configuration shape follows collect-all.

**Compact constructors shrink to normalization/defaults and do not throw for
operator mistakes.** This boundary is strict: a throwing binding constructor
prevents object construction, so Validator never runs and one error hides the
whole list. JSR-380 annotations remain declarative; registry checks (classification
predicates, `columns.from`, transforms) remain at bean creation because they need
registries; those beans must be eager.

Preflight internals are modular: one Spring-facing facade (`IocConfigPreflight` /
`IocConfigValidator`) forms one report, while small responsibility-specific rules
handle shape, reference integrity, literals/value types and policy. This keeps
one lifecycle without making the validator a god class.

<a id="2-ссылочная-целостность-конфига-закрывает-cfg-2"></a>
### 2. Configuration referential integrity (closes CFG-2)

Preflight checks, all config→config:

1. `artifact-identity.artifacts[].name` references an existing
   `sink.artifacts[].name`. **`enabled` is not required**: identity definitions
   are harmless and must survive temporary artifact disablement (unlike export
   profiles, where disabled = unknown because delivery is involved).
2. Every **enabled** sink artifact has an identity definition: first-write failure
   becomes startup failure.
3. `key-columns[]` ⊆ corresponding artifact columns, closing silent `null`
   in composite keys.
4. Artifact and identity-definition names are unique; column names are unique
   within each sink artifact.
5. `id.start` and `id.strategy` agree with the public `id` column: explicit
   numeric `id.start` is forbidden without a public ID.
6. Closed-vocabulary literals and `id.start` (Decision 5).

<a id="3-строгий-binding-неизвестный-ключ--ошибка-старта-закрывает-cfg-4"></a>
### 3. Strict binding: unknown key = startup failure (closes CFG-4)

First candidate: `@ConfigurationProperties(ignoreUnknownFields = false)`
(underneath, `NoUnboundElementsBindHandler`; systemEnvironment/systemProperties
are filtered by `UnboundElementsSourceFilter`, so false environment failures
should not occur). Constructor binding (records) + Boot 3.3.5 **must be tested
experimentally before commitment**. If customization is needed, fallback uses
an official binding seam (`ConfigurationPropertiesBindHandlerAdvisor` or
metadata-based preflight), strictly limited to root `IocProperties`/prefix `ioc`
to avoid affecting adapter-local binding such as
`IngestAdapterProperties(prefix = "ioc.ingestion")`. In either case add
`spring-boot-configuration-processor` to `bootstrap/ioc-app` for machine-readable
key metadata and IDE completion of operator YAML.

**Implementation note 2026-07-09.** The `ignoreUnknownFields=false` experiment
(B1) was performed and rejected: with the current record/list shape, Boot emits
false unbound failures for partial overlay/list overrides, and CLI/unknown-channel
reporting differs from the operator contract. Selected B2 without a YAML
preparser: `IocUnknownConfigurationPreflight` compares actual `ioc.*` property
names with the `IocProperties` reflection shape, stays limited to root prefix
`ioc` and leaves adapter-local binding alone. Legacy hints remain in `FailureAnalyzer`.

<a id="4-tombstones-удаляются-из-модели-подсказки-живут-в-failureanalyzer"></a>
### 4. Remove model tombstones; keep hints in FailureAnalyzer

Remove ghost fields (`Smb.readTimeout`, `Lookup` record) entirely from
`IocProperties`; strict binding now rejects unknown keys. Targeted migration
hints (“`ioc.lookup.deduplicate` moved to `ioc.pipeline.deduplicate`”,
“`read-timeout` → use `request-timeout`”) move to a custom `FailureAnalyzer`
(Description/Action) with a small legacy-key → hint map. Replace existing
tombstone tests with analyzer tests preserving message contracts.

**Do not couple FailureAnalyzer to `platform-diagnostics`:** lifecycles differ
(`Diagnostic` describes a running pipeline event with an ECS consumer; the
analyzer is a terminal operator explanation when the process dies at startup).
Technically, the analyzer also lives outside the assembled context
(`spring.factories`); no `DiagnosticSink` exists during binding failure.
The stable-code convention in message text may be borrowed.

<a id="5-типизация-значений-закрывает-cfg-1"></a>
### 5. Value typing (closes CFG-1)

Move closed `ioc.*` vocabularies from strings into enum/value types:
`key-mode`, `id.strategy`, `trigger.type`, `output-mode`, `ledger.type`,
`runtime.mode`, `observability.mode`, `engine`, `storage.*.type`,
`sync.endpoint.transport`, `maintenance.retention.targets[].action` and similar
selectors. Consumers stop comparing strings (`equalsIgnoreCase`) and use types.

If a selector participates in early Spring conditions, `EnvironmentPostProcessor`
or other locations before `IocProperties` binding, introduce one shared parser/
normalizer. Early consumers and `IocProperties` use one grammar instead of two
parallel string interpretations.

**`id.start`**, a tagged union (`"auto" | long`), receives one parser/value contract
used by preflight and `AppConfig.startOf`. Remove dead
`catch (NumberFormatException)`: invalid literals cannot reach runtime after
preflight. Sealed `IdStart` + `@ConfigurationPropertiesBinding` converter is an
acceptable final shape but not mandatory for one union field if a shared parser
already maintains the invariant.

<a id="6-конвенция-сообщений-об-ошибках-конфига"></a>
### 6. Configuration-error message convention

Speak the operator's language: key → value → how to fix. No internal jargon or
internal stage numbering (CFG-3's “stage 11” lesson).

<a id="следствия"></a>
## Consequences

- CFG-1, CFG-2 and CFG-4 close; CFG-3 closes by reference to the storage collapse;
  update KNOWN-ISSUES with implementation.
- Configuration errors do not survive startup: current first-write failures or
  silent mistakes become startup failures with an operator report.
- `IocProperties` shrinks: constructors only default, tombstone fields disappear;
  bootstrap gains `IocConfigPreflight` / `IocConfigValidator` and `FailureAnalyzer`.
- Negative tests use the real Boot binding/startup path
  (`ApplicationContextRunner`/minimal `SpringApplication`), not only manual
  `Binder`: check validator, strict binding, FailureAnalyzer, YAML typo,
  environment, CLI and overlay.

<a id="отклонённые-варианты"></a>
## Rejected alternatives

- **Expand compact-constructor validation:** throw-first fixes one error per
  run without property paths; constructors are already overloaded.
- **FailureAnalyzer ↔ DiagnosticSink:** categorically different channels; no
  receiving bean exists at boot failure, and a startup-failed process has no ECS consumer.
- **Two grammars for one selector:** early consumers and `IocProperties` must
  not parse identical strings independently. Extract a shared parser/normalizer
  if needed before binding.
- **Custom YAML strictness preparser:** outside Binder, consumed keys cannot be
  known (relaxed binding, nested maps); strictness belongs to binding.
- **General raw-environment preparser:** rejected as premature duplication of
  Binder's relaxed grammar. The 2026-07-10 addendum records an exception: after
  two failed public Boot-native spikes, a minimal raw-name-to-own-schema matcher
  is allowed, not a value parser or Binder replacement.

<a id="открытые-вопросы"></a>
## Open questions

1. Experimentally check `ignoreUnknownFields = false` on records/Boot 3.3.5
   (Decision 3); fallback to binding advisor or metadata preflight restricted
   to `IocProperties`/prefix `ioc`.
2. Decide stable message codes (`CONFIG.*`) during implementation (Decision 4:
   borrow the convention without DiagnosticSink).
3. Final `id.start` shape: sealed value object or shared parser/value contract
   without converter. One invariant is mandatory: identical preflight/runtime grammar.

**Closed 2026-07-09.**

1. B1 rejected; selected reflection-shape unknown-key preflight
   (`IocUnknownConfigurationPreflight`).
2. Stable `CONFIG.*` codes appear in startup/analyzer messages without coupling
   to `DiagnosticSink`.
3. `id.start` implemented as sealed `IdStart` (`auto`/`explicit(long)`) with
   `@ConfigurationPropertiesBinding` converter and normalized shared
   binding/preflight/runtime grammar.

<a id="план-реализации-по-срезам"></a>
## Implementation plan by slice

The plan below is the implementation execution checklist. The target model is
coherent: one startup preflight, strict binding, typed selectors, one early/late
consumer grammar and tombstones outside `IocProperties`. Prefer logical commit
groups: preflight lifecycle, strict binding/analyzer, value typing, docs/tracking.
Every slice preserves architecture: Spring/Boot specifics stay in
`bootstrap/ioc-app`, domain/application know no configuration, adapter-local
binding does not become the source of root-DSL rules.

<a id="срез-1--preflight-lifecycle-и-миграция-существующих-constructor-проверок-выполнен-b0e5157"></a>
### Slice 1 — Preflight lifecycle and existing constructor-check migration (completed: `b0e5157`)

**Goal.** Introduce one Spring-facing preflight seam and remove throw-first
operator errors from `IocProperties` compact constructors without new CFG-2 rules yet.

**Code changes:**

- `bootstrap/ioc-app/.../IocConfigPreflight.java`
  - New package-private/public final class implementing `org.springframework.validation.Validator`.
  - `supports(...)` returns `true` only for `IocProperties`.
  - `validate(...)` delegates to small rule methods/classes: `shape`,
    `localPolicy`, `syncReferences`, `legacyRemovedKeys`, etc.
  - Add errors through `Errors.rejectValue(...)` with property path, rejected
    value and operator message following `key → value → how to fix`.
  - Do not validate adapter-local `IngestAdapterProperties`.
- `bootstrap/ioc-app/.../ConfigPreflightConfiguration.java` or `AppConfig.java`
  - Add static bean `configurationPropertiesValidator`
    (`EnableConfigurationProperties.VALIDATOR_BEAN_NAME`).
  - Return `IocConfigPreflight`.
  - The bean must be `static` so Boot can create the validator during early
    binding without premature initialization of the normal configuration graph.
- `bootstrap/ioc-app/.../IocProperties.java`
  - Keep compact constructors only for defaults/normalization
    (`pipeline == null → default`, `ChangeNotify.disabled()`, `List.copyOf`).
  - Move checks currently throwing `IllegalArgumentException`
    (`dataframe.type`, `lookup.*`, `sync.retry`, `sync.endpoint`, fetch/publish
    endpoint references, sync publish export profile, `id.start` without public
    ID) into preflight rules.
  - Collection normalization must be null-safe: copy only non-null values,
    avoiding NPE before JSR-380/preflight.
- `bootstrap/ioc-app/src/test/java/.../IocPropertiesBindingTest.java`
  - New harness through `ApplicationContextRunner` or minimal
    `SpringApplication`/`SpringApplicationBuilder`, loading real `application.yml`.
  - Helper overrides through `TestPropertyValues`, temporary overlay and CLI-like args.
- Existing `IocPropertiesTest`
  - Keep pure Binder/default/parser checks or gradually move negative scenarios
    into the new Boot binding harness.

**Tests:**

- Default `application.yml` constructs `IocProperties` through real Boot binding.
- Several semantic errors on constructed configuration appear in one
  `BindValidationException`/failure report.
- Legacy `ioc.lookup.deduplicate`, removed lookup storage keys, non-JDBC
  dataframe type and invalid sync retry/backoff/profile references become field
  errors rather than compact-constructor failures.
- Missing/null nested values produce no constructor NPE and pass through
  JSR-380/preflight.

**Local verification:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,IocPropertiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary.** `CFG preflight lifecycle and constructor migration`.
May combine with slice 2 if small, but preferably establish lifecycle before new rules.

<a id="срез-2--configconfig-ссылочная-целостность-для-artifactsidentity-выполнен-9bb9e63"></a>
### Slice 2 — Config→config artifact/identity referential integrity (completed: `9bb9e63`)

**Goal.** Close the worst CFG-2 risk: an `artifact-identity` mistake must neither
create garbage durable identity state, fail on first write nor silently corrupt
`row_key`.

**Code changes:**

- `IocConfigPreflight`
  - Add rule `validateArtifactIdentityReferences`.
  - Build `sink.artifacts[].name → artifact` index without I/O.
  - Check uniqueness of `sink.artifacts[].name`.
  - Check uniqueness of `artifact-identity.artifacts[].name`.
  - Check uniqueness of `sink.artifacts[].columns[].name` within each artifact.
  - `artifact-identity.artifacts[].name` must reference an existing sink artifact;
    `enabled=false` is allowed.
  - Every `enabled` sink artifact requires an identity definition.
  - `key-columns[]` must be a subset of the corresponding artifact's columns.
  - Explicit numeric `id.start` is forbidden without a public `id` column.
  - Errors identify exact paths such as
    `ioc.artifact-identity.artifacts[2].key-columns[1]`,
    `ioc.sink.artifacts[0].columns[3].name`.
- `bootstrap/ioc-app/.../AppConfig.java`
  - After preflight, `artifactIdentityDefinitions(props)` may assume valid
    `keyMode`/`keyColumns`; do not add duplicate ad hoc validation.

**Tests:**

- typo in identity artifact name (`mask` vs `masks`) fails at startup;
- enabled sink artifact without identity fails at startup;
- identity for disabled-but-existing artifact is accepted;
- duplicate sink artifact names fail;
- duplicate identity artifact names fail;
- duplicate column names in one artifact fail;
- typo in composite `key-columns` fails before JDBC graph/first write;
- explicit numeric `id.start` on artifact without `id` column fails with
  operator-facing message;
- multiple identity/sink mistakes are reported together when binding shape is
  otherwise valid.

**Local verification:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary.** `CFG validate artifact identity references`.
Blast radius is bootstrap tests + no runtime I/O, so careful implementation may
combine this with slice 1.

<a id="срез-3--strict-binding-metadata-и-legacy-failureanalyzer-выполнен-ec14c8d"></a>
### Slice 3 — Strict binding, metadata and legacy FailureAnalyzer (completed: `ec14c8d`)

**Goal.** Make unknown `ioc.*` keys startup failures, remove model tombstones
and retain targeted migration hints.

**Code changes:**

- `bootstrap/ioc-app/.../IocProperties.java`
  - Set `@ConfigurationProperties(prefix = "ioc",
    ignoreUnknownFields = false)`.
  - Remove tombstone `Lookup` record and `lookup` field.
  - Remove `Sync.Endpoint.Smb.readTimeout`.
  - Remove bound-property legacy lookup preflight rules; these keys now enter
    the unbound/unknown flow.
- `bootstrap/ioc-app/pom.xml`
  - Add `spring-boot-configuration-processor` only in the runnable module,
    optional/annotation-processor style; neither parent dependencies nor runtime classpath.
- `bootstrap/ioc-app/.../IocConfigurationFailureAnalyzer.java`
  - Custom `FailureAnalyzer` for `UnboundConfigurationPropertiesException` /
    binding failures.
  - Small legacy-key/prefix → hint map:
    `ioc.lookup.deduplicate` → `moved to ioc.pipeline.deduplicate`,
    `ioc.lookup.*` → `legacy CSV lookup removed`,
    `ioc.sync.endpoints[].smb.read-timeout` → `use request-timeout`.
  - Description/Action use operator language and, if selected, stable `CONFIG.*` codes.
  - Analyzer has no `DiagnosticSink` / platform-diagnostics dependency.
- `bootstrap/ioc-app/src/main/resources/META-INF/spring.factories`
  - Register `org.springframework.boot.diagnostics.FailureAnalyzer=...`.
- If root-record `ignoreUnknownFields=false` misses required cases:
  - Use `ConfigurationPropertiesBindHandlerAdvisor` or metadata preflight,
    not a YAML preparser.
  - Restrict to `IocProperties`/prefix `ioc`, preserving
    `IngestAdapterProperties(prefix = "ioc.ingestion")`.

**Tests:**

- YAML typo under classpath/default-style overrides (`ioc.pipeline.deduplicat`)
  fails as unknown key;
- typo in optional overlay `./configs/application.yml` fails;
- CLI override `--ioc.pipeline.deduplicat=false` fails;
- system env/system properties do not produce false failures from unrelated
  `IOC_*`/system keys;
- old `ioc.lookup.deduplicate` and `smb.read-timeout` produce analyzer output
  with migration hints;
- valid env/CLI overrides for known keys still work;
- `IngestAdapterProperties` binding in daemon context is not broken by any
  custom strict-binding fallback.

**Local verification:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,IocConfigurationFailureAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary.** `CFG enable strict binding and legacy analyzer`.
Keep separate from value typing: strict binding is easier to debug independently
when operator startup UX regresses.

<a id="срез-4--typed-selectors-и-единая-грамматика-ранних-consumers-выполнен-da27b24-fix-30f1884"></a>
### Slice 4 — Typed selectors and one early-consumer grammar (completed: `da27b24`, fix `30f1884`)

**Goal.** Remove stringly typed closed vocabularies from `IocProperties` and
consumers, including early locations before root-property binding.

**Code changes:**

- `bootstrap/ioc-app/.../config` or `bootstrap` package
  - Introduce enum/value types `EngineType`, `RuntimeMode`, `ObservabilityMode`,
    `StorageType`, `ArtifactKeyMode`, `ArtifactIdStrategy`, `ExportTriggerType`,
    `ExportOutputMode`, `IngestionLedgerType`, `SyncTransport`,
    `RetentionActionType` (names may follow local style).
  - For pre-binding selectors add shared parser/normalizer
    (`RuntimeMode.parse(...)`, `StorageType.parse(...)`, etc.) with Boot enum
    binding's lenient behavior: case-insensitive, dash/underscore tolerant
    where appropriate.
- `IocProperties`
  - Replace closed-vocabulary string fields with enum/value types.
  - Remove literal preflight validation where binding already makes invalid
    values unrepresentable.
  - Retain preflight only for policy relationships between typed values.
- `AppConfig`, `SyncConfig`, `ExportPlanCatalog`,
  `DaemonWebEnvironmentPostProcessor`, `IocExtractorApplication`, `EarlyCliLauncher`
  - Replace `equalsIgnoreCase`/`valueOf(trim().toUpperCase(...))` with enum
    switches/typed checks or a shared early-environment parser.
  - `@ConditionalOnProperty`/`@ConditionalOnExpression` remain string-based only
    where Spring annotations prevent typed access; use constants/parser
    vocabulary rather than rewriting strings.
- `ExportPlanCatalog`
  - `outputMode` receives typed `ExportOutputMode`.
  - Unsupported `append` keeps its `EXPORT.UNSUPPORTED_MODE` diagnostic path
    as a business-policy failure, not a binding failure.
- `SyncConfig`
  - `SyncTransport.SMB` replaces `"smb".equalsIgnoreCase(...)`.
  - Unsupported transport becomes bind/preflight failure or typed enum-binding
    failure if only `SMB` is currently supported.

**Tests:**

- valid dash/case variants bind to enums (`first-non-empty`,
  `quiet-period`, `Re2J`, `DAEMON`);
- invalid selector value fails during binding with useful valid values;
- early runtime mode parser drives `DaemonWebEnvironmentPostProcessor` and
  `IocExtractorApplication` consistently with `IocProperties`;
- `ExportPlanCatalog` still rejects unsupported but syntactically valid
  `append` as `EXPORT.UNSUPPORTED_MODE`;
- sync transport typo fails before `TransportRegistry`;
- retention action typo fails before `RetentionService` graph.

**Local verification:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,ExportPlanCatalogTest,SyncSelectionCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary.** `CFG type closed configuration selectors`.
If too broad, split into `engine/runtime/storage/export`, then
`sync/ingestion/retention/identity`. Both must approach one target model without
two grammars for one selector.

<a id="срез-5--idstart-value-contract-и-удаление-silent-fallback-выполнен-3d45cdd-fix-e01e2fa"></a>
### Slice 5 — `id.start` value contract and silent fallback removal (completed: `3d45cdd`, fix `e01e2fa`)

**Goal.** Close CFG-1 completely: `id.start` becomes an `"auto" | long` value
contract with one binding/preflight/runtime grammar.

**Code changes:**

- `bootstrap/ioc-app/.../IdStart` / `ConfiguredIdStart`
  - Introduce a lightweight value object or sealed hierarchy: `auto`, `explicit(long)`.
  - Add one parser with an operator-facing error message.
  - For typed binding register
    `@ConfigurationPropertiesBinding Converter<String, IdStart>`.
  - For the lighter approach retain a string in `IocProperties`, but use the
    same parser in preflight and `AppConfig.startOf`.
- `IocProperties.Sink.Artifact.Id`
  - `start` becomes `IdStart`, or stays raw string only with a separately
    established and tested shared parser/value contract.
- `AppConfig.startOf(...)`
  - Remove `catch (NumberFormatException ignored)`.
  - Switch/pattern-match on `IdStart` or the shared parser result.
  - Invalid values cannot reach this point after preflight; if they do, throw
    `IllegalStateException`/`IocExtractorException`, not fallback to `auto`.
- `ExportPlanCatalog.mappingHash(...)`
  - Deterministically include normalized `auto`/explicit start so mapping-hash
    format does not depend on case/spacing.

**Tests:**

- `id.start: auto`, `AUTO`, ` auto ` yield auto semantics;
- numeric start yields explicit start;
- `10O0`, Cyrillic `аuto`, empty string and overflow fail at startup;
- explicit numeric start on an artifact without public ID still fails;
- `AppConfig.startOf` does not invoke baseline fallback for invalid literals;
- mapping hash is stable for normalized `auto`.

**Local verification:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,ExportPlanCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary.** `CFG parse id start explicitly`.
May combine with slice 4 if typed-selector migration is already small; when in
doubt keep separate because this is a separate tagged-union contract.

<a id="срез-6--registry-checks-eager-startup-и-сообщения-выполнен-8575527"></a>
### Slice 6 — Registry checks, eager startup and messages (completed: `8575527`)

**Goal.** Bring config→registry checks into the same startup UX: unknown
predicate/provider/transform/filter keys must not first appear during file
processing or writing.

**Code changes:**

- `AppConfig` / new bootstrap preflight component
  - Explicitly check classification predicate keys against `FeaturePredicates.defaults()`.
  - Check artifact `include`/`exclude` keys against `artifactFilters(...)` registry.
  - Check `columns[].from` against the value-provider registry plus `const`.
  - Check `columns[].transform[]` names against the transforms registry
    (`name:arg` is parsed by name).
  - Use operator language: property path, bad value, allowed values/action.
- `ConfigurableRowMapper`
  - A runtime guard may remain defense in depth, but no longer the normal first
    configuration-error detection point.
- Bean lifecycle
  - Ensure registry checks are eager in oneshot/daemon startup paths.
  - Do not perform heavy I/O or start lazy export/sync infrastructure solely
    to validate registry keys.

**Tests:**

- unknown classify predicate fails at startup with path
  `ioc.classify.rules[n].when[m]`;
- unknown artifact include/exclude predicate fails at startup;
- unknown column provider fails at startup;
- unknown transform fails at startup, including `name:arg` parsing;
- valid `const`, transform args and `when-type` remain accepted;
- failure messages do not mention internal stage numbers or implementation
  method names.

**Local verification:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=IocPropertiesBindingTest,ConfigRegistryPreflightTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Commit boundary.** `CFG validate registry-backed config eagerly`.
Combine with slice 2 only if registry maps are available without bloating
`IocConfigPreflight`; otherwise keep pure config→config and config→registry
boundaries separate.

<a id="срез-7--документы-tracking-и-финальная-верификация-выполняется-текущим-docstracking-commit"></a>
### Slice 7 — Documents, tracking and final verification (performed by the current docs/tracking commit)

**Goal.** Move ADR and KNOWN-ISSUES from design to shipped state after the code,
and establish the operator-facing contract in documentation.

**Documentation changes:**

- `docs/ADR/0016-config-preflight-strict-binding.md`
  - Append-style Status update: date, commit range, shipped slices.
  - Mark completed slices with commit IDs in this implementation plan.
  - Do not rewrite historical rationale.
- Local implementation ledger:
  - Mark the route implemented; promote durable outcomes into ADR/docs.
  - Remove or strike open questions closed by code.
- `docs/KNOWN-ISSUES.md`
  - Mark `CFG-1`, `CFG-2`, `CFG-4` closed with ADR/commit references.
  - Close `CFG-3` by reference to storage collapse / old-message removal.
  - Do not delete rows.
- `docs/ADR/README.md`
  - Update ADR 0016 summary to shipped state.
- `docs/dev/CROSS-CUTTING.md` or a more suitable dev/root document
  - Briefly describe configuration-preflight lifecycle, strict binding and
    operator-error convention if implementation establishes a durable
    capability-document-worthy contract.
- `bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/README.md`
  - Add new preflight/analyzer/parser classes to its reference table.
  - Establish composition-root ownership.

**Final verification:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Recommended commit sequence:**

1. `CFG preflight lifecycle and constructor migration`
2. `CFG validate artifact identity references`
3. `CFG enable strict binding and legacy analyzer`
4. `CFG type closed configuration selectors`
5. `CFG parse id start explicitly`
6. `CFG validate registry-backed config eagerly`
7. `Docs mark config preflight shipped`

For a single implementation pass, permitted combinations are slices 1+2
(one preflight lifecycle/reference commit), 4+5 (typed selectors + `id.start`
grammar), and 6+7 only if registry checks are small and docs do not obscure
the code diff. Prefer keeping slice 3 separate: strict binding and
FailureAnalyzer have their own startup-UX blast radius.
