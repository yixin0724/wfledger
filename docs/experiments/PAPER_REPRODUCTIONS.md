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
| Platform classes | `org.workflowsim.planning.cawosched.CaWoSchedPlanner`, `CaWoSchedTask`, `CaWoSchedPlan`, and `CaWoSchedAdapter` |
| Verification | `CaWoSchedPlannerTest` and `CaWoSchedAdapterTest`; registered mutation canary M32 |
| Status | `VERIFIED_CORE` for the compute-only ASAP/slack/pressure semantics and SHARED HEFT fixed-plan adaptation |

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

### Verified example

One four-second task, `P_idle=10W`, `P_active=30W`, deadline 15 seconds, with dirty `[0,5)`,
clean `[5,10)` (100W green), and dirty `[10,15)` windows:

- ASAP: start 0, carbon excess **180 W·s**;
- slack/pressure: start 5, carbon excess **100 W·s**.

This is a hand-derived verification case, not a universal improvement guarantee or a paper-table
reproduction. The adapter additionally verifies that a real SHARED HEFT plan remains dependency-
and mapping-compatible after conversion.

### Boundaries

- This is a post-planning component. The ordinary STATIC dispatcher still enforces mapping and
  order, not absolute start times; no new timed-dispatch event contract is claimed here.
- Communication processors, per-edge communication energy, the `W` power weighting, `R` interval
  refinement, local search, ILP, and the other CaWoSched variants are not yet implemented.
- The current adapter is SHARED compute-only. It does not claim equivalence to the paper's
  full-duplex communication-enhanced DAG or LOCAL transfer semantics.
- Workbench JSON and manifest protocol fields for timed CaWoSched runs are not introduced by this
  batch. No `CARBON_WINDOW_DEFER` result is relabeled as CaWoSched.
- Real-platform carbon calibration, general carbon optimality, and all 16 variants are not claimed.

See [algorithm contracts](<../algorithms/CONTRACTS.md>), the [mutation gate](<../advanced/MUTATION_GATE.md>),
and the [V&V case ledger](<VV_CASE_LEDGER.md>).
