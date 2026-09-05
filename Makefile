.DEFAULT_GOAL := help

# Developer-facing facade only. GitHub Actions intentionally call tools/* leaf
# scripts directly so workflow execution remains visible without parsing Make.

SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
MAKEFLAGS += --no-builtin-rules

MAVEN := ./mvnw -B -ntp -T 1C
MAVEN_SEQUENTIAL := ./mvnw -B -ntp -T 1
WORKSPACE ?= .dev/runtime
ONESHOT_WORKSPACE ?= .dev/oneshot
PORT ?= 18081
SMOKE_PORT ?= 18082
LIFECYCLE_PORT ?= 18083
JAR ?=
MODULE ?=
TEST ?=
SOURCE ?=
PROFILE ?= reputation-lists
IMPORT_PROFILE ?= insert
SIZE ?= 1000
SEED ?= 42
FORMAT ?= html
OUTPUT ?=
MANIFEST ?=
DUPLICATE_RATE ?=
DEFANG_RATE ?=
FORCE ?= 0
SMOKE ?= all
DB ?= dataframe
DB_COMMAND ?= shell
LOG_COMMAND ?= pretty
LOG_VALUE ?=
LOG_FILE ?=
FOLLOW ?= 0
PREVIOUS_TAG ?=
TARGET_REF ?= HEAD
GITHUB ?= 0

.PHONY: help \
	doctor doctor-core doctor-dev doctor-ci doctor-security bootstrap \
	clean package test test-fast test-integration test-module test-integration-module test-one verify version extract export \
	mutation-pilot \
	dependency-analysis pmd-analysis pmd-watchlist spotbugs-baseline-proposal \
	context \
	run stop runtime-up runtime-down runtime-status runtime-reset submit \
	fixture fixture-1k fixture-5k fixture-100k smoke smoke-cli smoke-oneshot smoke-daemon \
	lifecycle-smoke lifecycle-load dataframe-import-smoke dataframe-import-load dataframe-import-load-100k dataframe-import-load-1m \
	db logs logs-errors release-notes-context \
	lint-shell docs security-update security-scan security-report \
	ci-build ci-pmd ci-packaging ci-docs ci pre-push

help: ## Show this command reference
	@awk 'BEGIN {FS = ":.*## "; printf "Usage: make <target> [NAME=value ...]\n"} \
		/^##@/ {printf "\n%s\n", substr($$0, 5); next} \
		/^[a-zA-Z0-9_.-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Environment
context: ## Print stable project, Git, runtime, verify and PMD freshness context
	@tools/dev/context.sh --workspace "$(WORKSPACE)"

doctor: ## Check the complete local developer environment
	@tools/dev/doctor.sh all

doctor-core: ## Check Java, Git, Make and Maven Wrapper
	@tools/dev/doctor.sh core

doctor-dev: ## Check runtime, database and ECS-log prerequisites
	@tools/dev/doctor.sh dev

doctor-ci: ## Check local CI prerequisites
	@tools/dev/doctor.sh ci

doctor-security: ## Check Dependency-Check prerequisites and NVD_API_KEY
	@tools/dev/doctor.sh security

bootstrap: ## Install pinned repo-local tools missing from Ubuntu APT
	@tools/dev/bootstrap.sh lychee

##@ Build and tests
clean: ## Remove Maven build outputs (developer runtime data is preserved)
	@$(MAVEN) clean

package: ## Build the bootable jar without running tests
	@$(MAVEN) -DskipTests package

test: test-fast ## Run the fast Surefire suite across the reactor

test-fast: ## Run the fast Surefire suite across the reactor
	@$(MAVEN) test

test-integration: ## Run deterministic Failsafe suites and skipped external shells
	@modules="$$(find platform core adapters bootstrap -path '*/src/test/java/*' -type f \
		\( -name 'IT*.java' -o -name '*IT.java' -o -name '*ITCase.java' \) -printf '%p\n' \
		| cut -d/ -f1-2 | sort -u | paste -sd, -)"; \
		[[ -n "$${modules}" ]] || { echo "no integration-test modules found" >&2; exit 2; }; \
		$(MAVEN) -pl "$${modules}" -am -Dskip.unit.tests=true verify

test-module: ## Run one module's fast tests and upstream deps; MODULE=core/ioc-domain
	@[[ -n "$(MODULE)" ]] || { echo "MODULE is required" >&2; exit 2; }
	@[[ "$(MODULE)" =~ ^[A-Za-z0-9._/-]+$$ && -f "$(MODULE)/pom.xml" ]] || { echo "invalid Maven module: $(MODULE)" >&2; exit 2; }
	@$(MAVEN) -pl "$(MODULE)" -am test

test-integration-module: ## Run one module's Failsafe suites and upstream deps; MODULE=adapters/adapter-store-jdbc
	@[[ -n "$(MODULE)" ]] || { echo "MODULE is required" >&2; exit 2; }
	@[[ "$(MODULE)" =~ ^[A-Za-z0-9._/-]+$$ && -f "$(MODULE)/pom.xml" ]] || { echo "invalid Maven module: $(MODULE)" >&2; exit 2; }
	@find "$(MODULE)/src/test" -type f \( -name 'IT*.java' -o -name '*IT.java' -o -name '*ITCase.java' \) -print -quit 2>/dev/null \
		| grep -q . || { echo "module has no Failsafe suites: $(MODULE)" >&2; exit 2; }
	@$(MAVEN) -pl "$(MODULE)" -am -Dskip.unit.tests=true verify

test-one: ## Run one test selector; MODULE=... TEST=Class#method
	@[[ -n "$(MODULE)" && -n "$(TEST)" ]] || { echo "MODULE and TEST are required" >&2; exit 2; }
	@[[ "$(MODULE)" =~ ^[A-Za-z0-9._/-]+$$ && -f "$(MODULE)/pom.xml" ]] || { echo "invalid Maven module: $(MODULE)" >&2; exit 2; }
	@printf '%s\n' "$(TEST)" | grep -Eq '^[A-Za-z0-9_.$$#]+$$' \
		|| { echo "TEST must be an exact Class or Class#method selector" >&2; exit 2; }
	@selector="$(TEST)"; class="$${selector%%#*}"; simple="$${class##*.}"; \
		find "$(MODULE)/src/test" -type f -name "$${simple}.java" -print -quit 2>/dev/null \
		| grep -q . || { echo "test class not found in $(MODULE): $${simple}" >&2; exit 2; }
	@selector="$(TEST)"; class="$${selector%%#*}"; simple="$${class##*.}"; \
		if [[ "$${simple}" == IT* || "$${simple}" == *IT || "$${simple}" == *ITCase ]]; then \
			$(MAVEN) -pl "$(MODULE)" -am -Dskip.unit.tests=true verify \
				-Dit.test="$(TEST)" -Dfailsafe.failIfNoSpecifiedTests=false; \
		else \
			$(MAVEN) -pl "$(MODULE)" -am test -Dtest="$(TEST)" \
				-Dsurefire.failIfNoSpecifiedTests=false; \
		fi

mutation-pilot: ## Run the report-only PIT pilot for core/ioc-domain
	@$(MAVEN_SEQUENTIAL) -pl core/ioc-domain -Pmutation-pilot \
		test-compile org.pitest:pitest-maven:mutationCoverage

verify: ## Run the release-quality Maven reactor gate
	@tools/ci/build.sh

pmd-analysis: ## Run the blocking/advisory PMD production-source policy
	@tools/ci/pmd.sh policy

pmd-watchlist: ## Run the deferred PMD ownership/size watchlist
	@tools/ci/pmd.sh watchlist

spotbugs-baseline-proposal: ## Render a non-accepting SpotBugs baseline delta from current raw reports
	@$(MAVEN_SEQUENTIAL) -N validate
	@java -cp target/build-quality-verifier SpotBugsBaselineVerifier propose \
		"$(CURDIR)" \
		"$(CURDIR)/build-support/spotbugs-report/spotbugs-scope.tsv" \
		"$(CURDIR)/build-support/spotbugs-report/spotbugs-accepted-findings.xml" \
		"$(CURDIR)/target/build-quality/spotbugs-baseline-proposal.xml"

##@ Application
version: package ## Print build identity from the runnable jar
	@tools/dev/app.sh $(if $(JAR),--jar "$(JAR)") --version

extract: package ## Extract SOURCE in isolated ONESHOT_WORKSPACE=.dev/oneshot
	@[[ -n "$(SOURCE)" ]] || { echo "SOURCE is required" >&2; exit 2; }
	@source="$$(realpath -e -- "$(SOURCE)")"; \
		tools/dev/app.sh --workspace "$(ONESHOT_WORKSPACE)" $(if $(JAR),--jar "$(JAR)") extract --source "$${source}"

export: package ## Export PROFILE from the isolated one-shot storage
	@tools/dev/app.sh --workspace "$(ONESHOT_WORKSPACE)" $(if $(JAR),--jar "$(JAR)") export --profile "$(PROFILE)"

run: runtime-up ## Start the isolated developer daemon

stop: runtime-down ## Stop the isolated developer daemon

runtime-up: package ## Start daemon; optional WORKSPACE=... PORT=... JAR=...
	@args=(--workspace "$(WORKSPACE)" --port "$(PORT)"); \
		[[ -z "$(JAR)" ]] || args+=(--jar "$(JAR)"); \
		tools/dev/runtime.sh "$${args[@]}" up

runtime-down: ## Stop the validated daemon process
	@tools/dev/runtime.sh --workspace "$(WORKSPACE)" down

runtime-status: ## Show daemon process and health status
	@tools/dev/runtime.sh --workspace "$(WORKSPACE)" status

runtime-reset: ## Stop daemon and delete only its validated .dev workspace
	@tools/dev/runtime.sh --workspace "$(WORKSPACE)" reset

submit: ## Atomically submit SOURCE to the developer daemon inbox
	@[[ -n "$(SOURCE)" ]] || { echo "SOURCE is required" >&2; exit 2; }
	@tools/dev/submit.sh --workspace "$(WORKSPACE)" "$(SOURCE)"

##@ Fixtures and inspection
fixture: ## Generate IOC fixture; SIZE=... SEED=... FORMAT=html|text
	@args=(--size "$(SIZE)" --seed "$(SEED)" --format "$(FORMAT)"); \
		[[ -z "$(OUTPUT)" ]] || args+=(--output "$(OUTPUT)"); \
		[[ -z "$(MANIFEST)" ]] || args+=(--manifest "$(MANIFEST)"); \
		[[ -z "$(DUPLICATE_RATE)" ]] || args+=(--duplicate-rate "$(DUPLICATE_RATE)"); \
		[[ -z "$(DEFANG_RATE)" ]] || args+=(--defang-rate "$(DEFANG_RATE)"); \
		[[ "$(FORCE)" != 1 ]] || args+=(--force); \
		tools/dev/fixture.sh "$${args[@]}"

fixture-1k: SIZE=1000
fixture-1k: fixture ## Generate the standard 1,000-row fixture

fixture-5k: SIZE=5000
fixture-5k: fixture ## Generate the standard 5,000-row fixture

fixture-100k: SIZE=100000
fixture-100k: fixture ## Generate the standard 100,000-row fixture

smoke: package ## Run smoke subset; SMOKE=cli|oneshot|daemon|all SMOKE_PORT=18082
	@DEV_SMOKE_JAR="$(JAR)" DEV_SMOKE_PORT="$(SMOKE_PORT)" tools/dev/smoke.sh "$(SMOKE)"

smoke-cli: SMOKE=cli
smoke-cli: smoke ## Check public CLI identity and help

smoke-oneshot: SMOKE=oneshot
smoke-oneshot: smoke ## Check extraction, storage and immutable export

smoke-daemon: SMOKE=daemon
smoke-daemon: smoke ## Check daemon ingest and actuator health

lifecycle-smoke: package ## Exercise expiry, history retention and ID non-reuse; SIZE=1000
	@args=(--size "$(SIZE)" --port "$(LIFECYCLE_PORT)"); \
		[[ -z "$(JAR)" ]] || args+=(--jar "$(JAR)"); \
		tools/dev/lifecycle-smoke.sh "$${args[@]}"

lifecycle-load: SIZE=66667
lifecycle-load: package ## Run the 100k lifecycle reference profile; override SIZE/TTL/TIMEOUT via script
	@args=(--size "$(SIZE)" --ttl 2m --timeout 1200 --port "$(LIFECYCLE_PORT)" \
		--workspace .dev/lifecycle-load-$(SIZE) --min-canonical-rows 100000 \
		--export-quiet-period 30s \
		--max-deadline-spread-ms 30000 --min-expiry-rows-per-second 2500 \
		--max-retention-seconds 180 --max-rss-kib 1048576); \
		[[ -z "$(JAR)" ]] || args+=(--jar "$(JAR)"); \
		tools/dev/lifecycle-smoke.sh "$${args[@]}"

dataframe-import-load: ## Run opt-in full import load; IMPORT_PROFILE=insert|mixed SIZE=100000
	@tools/dev/dataframe-import-load.sh --profile "$(IMPORT_PROFILE)" --size "$(SIZE)"

dataframe-import-smoke: package ## Run local managed-import ownership/commit/terminal/projection smoke
	@tools/dev/smoke.sh import

dataframe-import-load-100k: IMPORT_PROFILE=insert
dataframe-import-load-100k: SIZE=100000
dataframe-import-load-100k: dataframe-import-load ## Qualify the 100k full-import baseline

dataframe-import-load-1m: IMPORT_PROFILE=mixed
dataframe-import-load-1m: SIZE=1000000
dataframe-import-load-1m: dataframe-import-load ## Qualify the 1M mixed-import release profile

db: ## Inspect SQLite read-only; DB=service|dataframe DB_COMMAND=shell|schema|tables
	@tools/dev/database.sh --workspace "$(WORKSPACE)" --db "$(DB)" "$(DB_COMMAND)"

logs: ## Query ECS logs; LOG_COMMAND=pretty|errors|event|run|diagnostic|raw
	@args=(--workspace "$(WORKSPACE)"); \
		[[ -z "$(LOG_FILE)" ]] || args+=(--file "$(LOG_FILE)"); \
		[[ "$(FOLLOW)" != 1 ]] || args+=(--follow); \
		args+=("$(LOG_COMMAND)"); \
		[[ -z "$(LOG_VALUE)" ]] || args+=("$(LOG_VALUE)"); \
		tools/dev/logs.sh "$${args[@]}"

logs-errors: LOG_COMMAND=errors
logs-errors: logs ## Print ERROR/FATAL ECS events

##@ Release preparation
release-notes-context: ## Collect release-note inputs; PREVIOUS_TAG=vX.Y.Z TARGET_REF=HEAD GITHUB=0|1
	@[[ -n "$(PREVIOUS_TAG)" ]] || { echo "PREVIOUS_TAG is required" >&2; exit 2; }
	@args=(--previous-tag "$(PREVIOUS_TAG)" --target "$(TARGET_REF)"); \
		[[ "$(GITHUB)" != 1 ]] || args+=(--github); \
		tools/dev/release-notes-context.sh "$${args[@]}"

##@ Quality and security
dependency-analysis: ## Run the fast report-only Maven dependency analysis
	@$(MAVEN_SEQUENTIAL) -DskipTests package dependency:analyze-only

lint-shell: ## Run ShellCheck and packaging/tools contract tests
	@tools/ci/packaging.sh

docs: ## Check local documentation links offline
	@tools/ci/docs.sh

security-update: ## Update the local NVD cache (requires NVD_API_KEY)
	@tools/ci/dependency-security.sh update

security-scan: ## Scan offline using existing NVD data; fail at CVSS 7+
	@tools/ci/dependency-security.sh scan

security-report: ## Print existing Dependency-Check report paths
	@tools/ci/dependency-security.sh report

##@ CI parity
ci-build: ## CI leaf: Maven verify
	@tools/ci/build.sh

ci-pmd: ## CI leaf: adopted PMD production-source policy
	@tools/ci/pmd.sh policy

ci-packaging: ## CI leaf: ShellCheck and shell contracts
	@tools/ci/packaging.sh

ci-docs: ## CI leaf: offline documentation links
	@tools/ci/docs.sh

ci: ## Run the same regular gates as GitHub CI, sequentially
	@$(MAKE) --no-print-directory ci-pmd
	@$(MAKE) --no-print-directory ci-build
	@$(MAKE) --no-print-directory ci-packaging
	@$(MAKE) --no-print-directory ci-docs

pre-push: ci ## Local pre-push gate (scheduled NVD scan remains separate)
