# P5 policy identity and activation

Status: implementation evidence, 2026-09-28. The release worknote is not an
operator contract; use the published processing capability and configuration
guide for supported configuration.

## Activated boundaries

- A selected `ioc.processing.document-plan` builds one Camel runtime at Spring
  startup. The oneshot and daemon extraction factory choose the routed document
  path only for that selection. Each document invocation supplies its own CSV
  preparers, preserving the run's source key and independent ID preparation.
- A processed import contract may select a named plan and explicitly bind
  ordered `(artifact, target)` input cells and authorized output targets. The
  existing import mapper still owns input admission, final keys and validation.
  AS_IS contracts and processed contracts without a route use the compatible
  path. A route cannot add an artifact, replace source authority or produce
  more than one branch per contract artifact in this version.
- All selected plans share one registered Camel context. Branch dispatch carries
  both plan and branch IDs, so a shared destination cannot mix the per-plan
  default or field-view binding. IOC parsing, classification, mapping and
  canonical transaction owners are unchanged.

## Identity and recovery

The processing fingerprint uses epoch `processing-policy:v3`, includes ordered
plan declarations, selected document plan and explicit semantic versions for
document routing, processed import routing, exact parsing and host derivation.
The compiled import contract fingerprint uses descriptor v3 and includes its
route selector and ordered input/output bindings. Existing pins therefore do
not silently adopt a new policy. This is deliberately conservative: changing an
unrelated declared plan also changes the global processing identity and may
block an unsealed pinned import until the previous configuration is restored.

For `CONTRACT_PINNED` or `STAGING`, a sealed stage matching the durable pin is
adopted. Otherwise the active contract is recognized and compared with the pin
*before* workspace rebuild or row mapping. A missing or changed contract is a
consistency barrier, not a terminal row rejection. `STAGED` and `PROMOTING` use
sealed evidence; `CANONICAL_COMMITTED` and `FINALIZING` finish from the dataframe
receipt without parsing the snapshot again. An incompatible unsealed head blocks
the ordered import lane; restore the old configuration and drain it before a
policy change. There is no executable old-plan archive or automatic backfill.

Document intake uses a service schema v12 policy marker. On a policy change,
daemon startup checks the incomplete ingestion ledger, processing files and
document admissions that are nonterminal or await registration finalization
before updating the marker. An unchanged
fingerprint may recover its own in-flight work. Existing installations with
pending legacy work must drain it before first route activation. The gate checks
at startup, before the ingestion service is exposed. The marker uses the full
processing fingerprint, so editing another declared plan after activation can
also require a drain. This startup check assumes the deployment's single active
daemon per service DB; it does not coordinate simultaneous daemons running
different policies. One-shot runs have no
durable intake queue; each invocation uses its current selected plan.

Canonical data is never rekeyed or rewritten on policy activation. Old and new
rows may coexist. Fixed lifecycle expiry removes old active rows according to
their own validity deadline; with lifecycle disabled, no expiry is promised.
The service schema migration is one-way, as with other SQLite schema versions;
rollback to an older binary requires a compatible service DB snapshot.

## Evidence and P6 boundary

Tests cover ordered plan fingerprints, import contract route fingerprints,
pre-rebuild pin checks, shared-runtime plan isolation, Spring route activation,
and service-DB policy gate restart/change behavior, including absent storage,
unchanged restart, and both external drain checks. Two integration suites and
one selected-factory unit suite raise the accepted test universe from 68 to 70
integration suites, from 216 to 217 fast suites, and from 279 to 282
deterministic offline suites without reducing coverage floors. The route
validation matrix covers malformed, ambiguous, unauthorized and
authority-replacing bindings, plus incomplete route references and duplicate
destinations. Startup tests include the default branch in the output binding
set and tolerate incomplete catalogs until semantic validation reports them.
Restart tests also reject a stager that returns
a different contract pin or loses a pinned source during recognition.

A clean Maven `javac` SpotBugs review retained the existing `EI_EXPOSE_REP`
findings. Two existing lifecycle lambdas intentionally rethrow the primary
runtime failure after recording terminal/observer state. Their
`THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` identities changed because new
bootstrap wiring shifted compiler-generated lambda names; the exact baseline
was updated to the current raw method names, hashes and source anchors. The
accepted identity count remains 120; no rule or analyzer scope was excluded.

Existing receipt recovery tests cover post-commit finalization; P6 still owns customer golden fixtures,
before/after resource measurements and retirement of the compatible processed
mapping path.
