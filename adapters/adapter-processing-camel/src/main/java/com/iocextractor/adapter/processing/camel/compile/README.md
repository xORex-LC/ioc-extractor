# com.iocextractor.adapter.processing.camel.compile

## Purpose and structure

Startup admission, once-bound selectors and generated local Camel routes.
Follow the [module dependency rules](../../../../../../../../../README.md). This package
knows Camel processors and technical descriptors, without IOC or Spring policy.

| File | Responsibility |
|---|---|
| [CamelPlanCompiler.java](CamelPlanCompiler.java) | Validate plan inventory, capture processors/scopes, generate local view/branch routes and compile selectors |
| [PlanValidator.java](PlanValidator.java) | IDs, graph/reference checks, condition limits and recovery/default rules |
| [OperationCatalog.java](OperationCatalog.java) | Copied registries, allowed predicate arguments, predicate factories and recoverable reasons |
| [CompiledSelector.java](CompiledSelector.java) | Once-bound condition graph and ordered tri-state FIRST/ALL/EXCLUSIVE evaluation |
| [CompiledRoutes.java](CompiledRoutes.java) | Route/endpoint inventory and view/branch/selector metadata for runtime |
| [PlanAdmissionException.java](PlanAdmissionException.java) | IllegalArgumentException carrying an admission location in its message |
| [RouteProtocol.java](RouteProtocol.java) | Plan/branch exchange header keys shared with registered destinations |

## Admission and binding

`CamelPlanCompiler.compile` is the complete entry point: it rejects duplicate
plan IDs, calls the validator, binds predicates and captures operation/destination
processors. Calling `CompiledSelector.compile` directly assumes already admitted
descriptors. Predicate argument names are checked against the registration;
required argument/value semantics belong to its factory. A null or throwing
factory result fails compilation rather than being deferred to an invocation.

| Structural bound | Limit |
|---|---|
| Plans in one compilation | 32 |
| Derived views per plan | 64 |
| Regular branches per plan | 64; a default branch is checked separately |
| Total condition nodes across regular branches | 256 |
| Condition nesting depth | 16 |

Plan/view/branch IDs follow `[a-z][a-z0-9_-]{0,63}`; `original` is reserved.
View dependencies must exist and be acyclic; shared dependencies are allowed.
Recovery requires an earlier alternate (or `original`), a derived primary and
an explicit non-empty registered reason allowlist without `*`. Chained recovery
and an alternate depending on its primary are rejected. Unknown/duplicate
required views, unknown predicates/destinations/operations and empty condition
groups fail admission. ROUTE needs a matching separate default without an
eligibility condition; SKIP/REJECT cannot carry a default. These structural
limits do not bound payload size, callback duration or arbitrary operation cost.

Processors, predicate bindings and scope objects are retained references.
Registry/argument collections are snapshots; callback implementations must be
reusable and safe across concurrent invocations. No invocation value enters a
shared argument cache. `CompiledRoutes` copies inventories but does not deep-freeze
Camel RouteBuilders or callbacks; treat the compiled inventory as startup-owned.

## Selection semantics

Conditions evaluate in declaration order and short-circuit. ALL stops on the
first no-match, ANY on the first match, and either stops on a reached blocked
operand. NOT preserves blocked. A later operand is not evaluated to erase an
earlier failure.

| Mode | Selection |
|---|---|
| FIRST | Stop at first match or reached blockage; never try a later branch after selected-branch preparation/destination failure |
| ALL | Evaluate regular branches in order; retain matches and blocked evidence independently; matching siblings may still run |
| EXCLUSIVE | Require one match and conclusive no-matches elsewhere; a reached blockage prevents dispatch, while a second reached match immediately yields ambiguity |

Unmatched actions run only after conclusive no-match. A blockage is not a
reason to take the default. Selection completes before runtime dispatch.

## Generated route protocol

Only fixed `direct:` addresses derived from validated IDs are generated, with
`block=false&failIfNoConsumers=true`. Recovery views are runtime metadata, not
another operation route. Operations return `ViewOutcome`; branch callbacks
receive `BranchInput` and return `BranchOutcome`. A missing/wrong typed reply
fails execution. Branch routes install `RouteProtocol.PLAN_ID` and `BRANCH_ID`
before invoking the callback and use the branch header to build `BranchReply`;
registered code must preserve those protocol headers.

Each callback runs inside a caller-provided scope and Camel's native consumer
UnitOfWork. Routes use `noErrorHandler()`; there is no configured redelivery,
Recipient List, aggregation route or whole-invocation transaction. The
[runtime](../runtime/README.md) binds endpoints after context startup and
owns per-call evaluation/dispatch. The [test package](../../../../../../../../test/java/com/iocextractor/adapter/processing/camel/README.md)
maps admission, ordering and native execution conformance.
