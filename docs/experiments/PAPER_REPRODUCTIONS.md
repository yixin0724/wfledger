# Paper Algorithm Reproductions

This ledger records literature algorithms implemented in the platform, their reference artifacts,
and the exact scope of the implementation. "Implemented" never means "all paper experiments,
numbers, hardware, or real-platform behavior reproduced."

## CaWoSched — ICPP 2025

| Item | Record |
|---|---|
| Paper | Dominik Schweisgut, Anne Benoit, Yves Robert, Henning Meyerhenke, *Carbon-Aware Workflow Scheduling with Fixed Mapping and Deadline Constraint*, ICPP 2025 |
| Primary source | [DOI](https://doi.org/10.1145/3754598.3754673), [arXiv:2507.08725v2](https://arxiv.org/html/2507.08725v2) |
| Official artifact | [KIT-EAE/CaWoSched](https://github.com/KIT-EAE/CaWoSched), commit [`dfc8e49c692bb6178bb88514a9f9e8fb8b1bdf7b`](https://github.com/KIT-EAE/CaWoSched/commit/dfc8e49c692bb6178bb88514a9f9e8fb8b1bdf7b) |
| License | [MIT](https://github.com/KIT-EAE/CaWoSched/blob/dfc8e49c692bb6178bb88514a9f9e8fb8b1bdf7b/LICENSE) |
| Platform classes | `org.workflowsim.planning.cawosched.CaWoSchedPlanner`, `CaWoSchedVariant`, `CaWoSchedOptimizer`, `CaWoSchedAdapter`, `CaWoSchedPlanningAlgorithm`, plus `org.workflowsim.planning.timed` and `CommunicationEnhancedPlan` |
| Verification | `CaWoSchedPlannerTest`, `CaWoSchedAdapterTest`, `TimedPlanFrameworkTest`, `CaWoSchedTimedPlanIntegrationTest`, `CommunicationEnhancedPlanTest`, `CaWoSchedExactOptimizerTest`, `CaWoSchedTimedExecutionIntegrationTest`; M32–M35 |
| Status | `VERIFIED_TIMED_CORE` for all 16 compute-only variants, communication-enhanced activities, exact single-processor DP, and absolute-start dispatch |

### Implemented semantics

- A fixed Task-to-VM mapping and per-VM order are preserved. The implementation adds the
  corresponding precedence edges and rejects cycles, overlap, missing dependencies, and infeasible
  deadlines.
- ASAP places every task at its earliest feasible start.
- Initial EST/LST bounds produce `slack = LST - EST` and
  `pressure = duration / (LST + duration - EST)`. Slack is ascending and pressure descending,
  with deterministic task-ID tie-breaks.
- A task is placed at the highest-green-budget interval start in its current `[EST,LST]` range;
  equal budgets use the earliest start, and no candidate falls back to EST. EST/LST bounds are
  recomputed after each decision.
- Carbon excess is independently integrated over all task and window boundaries:
  `Σ max(0, P_idle + P_dynamic(t) - G(t)) dt` over the complete `[0,deadline)` horizon.
- `CaWoSchedAdapter` converts a real SHARED HEFT trace into the compute-only input while preserving
  mapping, per-VM order, dependencies, raw compute duration, and declared VM power.
- The generic `TimedPlan`, `PlanObjectiveEvaluator`, and `PostPlanningOptimizer` APIs decouple plan
  representation, independent metrics, and optimization. `CaWoSchedVariant` exposes all 16
  score/weight/refinement/local-search combinations through this API. R creates block-based refined
  intervals and consumes their green budgets; LS accepts only strictly carbon-improving legal moves.
- `CommunicationEnhancedPlan` turns each cross-VM dependency into an ordered communication activity
  on an explicitly declared link with power and per-edge durations. Missing links fail fast.
- `planExactSingleProcessor()` performs exact dynamic programming for small ordered activities and
  is checked against independent integer-start enumeration. `CAWOSCHED_SLACK` and
  `CAWOSCHED_PRESSURE` additionally write absolute starts into the standard STATIC plan and use a
  deferral-aware wake-up path to execute them.

### Verified example

One four-second task, `P_idle=10W`, `P_active=30W`, deadline 15 seconds, with dirty `[0,5)`,
clean `[5,10)` (100W green), and dirty `[10,15)` windows:

- ASAP: start 0, carbon excess **180 W·s**;
- slack/pressure: start 5, carbon excess **100 W·s**.

This is a hand-derived verification case, not a universal improvement guarantee or a paper-table
reproduction. The adapter additionally verifies that a real SHARED HEFT plan remains dependency-
and mapping-compatible after conversion.

### Boundaries

- Absolute starts are enforced only for `CAWOSCHED_SLACK` and `CAWOSCHED_PRESSURE` through an
  explicit deferral-aware wake-up path. Existing STATIC mappings and order semantics are unchanged.
- Communication activities and exact single-processor DP are implemented, but the full multi-
  processor ILP and full paper corpus are not. The adapter remains SHARED compute-only and does not
  claim that stage-in or every LOCAL transfer model is identical to the paper's communication model.
- Workbench JSON and manifest protocol fields for timed CaWoSched runs are not introduced by this
  batch. No `CARBON_WINDOW_DEFER` result is relabeled as CaWoSched.
- Real-platform carbon calibration, general carbon optimality, and full paper-corpus reproduction
  are not claimed.

See [algorithm contracts](<../algorithms/CONTRACTS.md>), the [mutation gate](<../advanced/MUTATION_GATE.md>),
and the [V&V case ledger](<VV_CASE_LEDGER.md>).
