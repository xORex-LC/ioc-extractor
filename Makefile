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
JAR ?=
MODULE ?=
TEST ?=
SOURCE ?=
PROFILE ?= reputation-lists
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
	clean package test test-module test-one verify version extract export \
	dependency-analysis \
	context \
	run stop runtime-up runtime-down runtime-status runtime-reset submit \
	fixture fixture-1k fixture-5k fixture-100k smoke smoke-cli smoke-oneshot smoke-daemon \
	db logs logs-errors release-notes-context \
	lint-shell docs security-update security-scan security-report \
	ci-build ci-packaging ci-docs ci pre-push

help: ## Show this command reference
	@awk 'BEGIN {FS = ":.*## "; printf "Usage: make <target> [NAME=value ...]\n"} \
		/^##@/ {printf "\n%s\n", substr($$0, 5); next} \
		/^[a-zA-Z0-9_.-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Environment
context: ## Print stable key=value project, Git, runtime and verify context
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

test: ## Run the Maven test phase across the reactor
	@$(MAVEN) test

test-module: ## Test one module and upstream deps; MODULE=core/ioc-domain
	@[[ -n "$(MODULE)" ]] || { echo "MODULE is required" >&2; exit 2; }
	@[[ "$(MODULE)" =~ ^[A-Za-z0-9._/-]+$$ && -f "$(MODULE)/pom.xml" ]] || { echo "invalid Maven module: $(MODULE)" >&2; exit 2; }
	@$(MAVEN) -pl "$(MODULE)" -am test

test-one: ## Run one test selector; MODULE=... TEST=Class#method
	@[[ -n "$(MODULE)" && -n "$(TEST)" ]] || { echo "MODULE and TEST are required" >&2; exit 2; }
	@[[ "$(MODULE)" =~ ^[A-Za-z0-9._/-]+$$ && -f "$(MODULE)/pom.xml" ]] || { echo "invalid Maven module: $(MODULE)" >&2; exit 2; }
	@printf '%s\n' "$(TEST)" | grep -Eq '^[A-Za-z0-9_.$$#]+$$' \
		|| { echo "TEST must be an exact Class or Class#method selector" >&2; exit 2; }
	@selector="$(TEST)"; class="$${selector%%#*}"; simple="$${class##*.}"; \
		find "$(MODULE)/src/test" -type f -name "$${simple}.java" -print -quit 2>/dev/null \
		| grep -q . || { echo "test class not found in $(MODULE): $${simple}" >&2; exit 2; }
	@$(MAVEN) -pl "$(MODULE)" -am test -Dtest="$(TEST)" -Dsurefire.failIfNoSpecifiedTests=false

verify: ## Run the release-quality Maven reactor gate
	@tools/ci/build.sh

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

ci-packaging: ## CI leaf: ShellCheck and shell contracts
	@tools/ci/packaging.sh

ci-docs: ## CI leaf: offline documentation links
	@tools/ci/docs.sh

ci: ## Run the same regular gates as GitHub CI, sequentially
	@$(MAKE) --no-print-directory ci-build
	@$(MAKE) --no-print-directory ci-packaging
	@$(MAKE) --no-print-directory ci-docs

pre-push: ci ## Local pre-push gate (scheduled NVD scan remains separate)
