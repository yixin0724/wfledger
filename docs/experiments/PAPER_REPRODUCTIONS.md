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

## CWS RankMin-RR — CCGrid 2023

| Item | Record |
|---|---|
| Paper | Fabian Lehmann, Jonathan Bader, Friedrich Tschirpke, Lauritz Thamsen, Ulf Leser, *How Workflow Engines Should Talk to Resource Managers: A Proposal for a Common Workflow Scheduling Interface*, CCGrid 2023 |
| Primary source | [DOI](https://doi.org/10.1109/CCGrid57682.2023.00025), [arXiv:2302.07652v3](https://arxiv.org/html/2302.07652v3) |
| Official artifact | [CommonWorkflowScheduler/KubernetesScheduler](https://github.com/CommonWorkflowScheduler/KubernetesScheduler), commit [`3046e19d5a3a237c5f6c0df4d16f33e643b31828`](https://github.com/CommonWorkflowScheduler/KubernetesScheduler/tree/3046e19d5a3a237c5f6c0df4d16f33e643b31828) |
| License | [GPLv3](https://github.com/CommonWorkflowScheduler/KubernetesScheduler/blob/3046e19d5a3a237c5f6c0df4d16f33e643b31828/LICENSE) |
| Platform classes | `CwsRankMinRoundRobinSchedulingAlgorithm`, `Task.cwsRank`, and `WorkflowDagValidator` bottom-rank assignment |
| Verification | `CwsDagRankTest`, `CwsRankMinRoundRobinSchedulingAlgorithmTest`, `CwsFigureOneIntegrationTest`; M36 |
| Status | `VERIFIED_STATIC_SUBSET` for the static-DAG/idle-VM CWS RankMin-RR adaptation |

CWS rank is the longest descendant PROCESS path, not HEFT's weighted upward rank. Ready Jobs are
ordered by descending rank, then ascending real input bytes and ascending Job ID; VM selection is
the maintained persistent round-robin over currently idle PE-compatible VMs. The physical Figure 1
DAG is reproduced explicitly: `t1→t2/t3/t4`, `t3/t4→t5`, and `t2/t5→t6`, with ranks
`[4,2,3,3,2,1]`. In the controlled runner, FCFS completes in **5.21 s** and CWS in **4.21 s**,
matching the paper's five-to-four-unit direction after the declared stage-in/tick overhead. The
static adaptation does not claim the dynamic Nextflow/Kubernetes API, future task creation, or
real-cluster performance.

## NACO 2023 — Hybrid GA/HEFT with DNC

| Item | Record |
|---|---|
| Paper | Pablo Barredo, Jorge Puente, *Precise makespan optimization via hybrid genetic algorithm for scientific workflow scheduling problem*, Natural Computing 22, 615–630, 2023 |
| Primary source | [DOI](https://doi.org/10.1007/s11047-023-09950-5), [University of Oviedo record](https://hdl.handle.net/10651/70610) |
| Official artifact | [iScOp-uniovi/Paper_NACO_Barredo_2023](https://github.com/iScOp-uniovi/Paper_NACO_Barredo_2023), commit [`b2f42bed2584022433dfe270612fe777ec8d1f58`](https://github.com/iScOp-uniovi/Paper_NACO_Barredo_2023/tree/b2f42bed2584022433dfe270612fe777ec8d1f58) |
| License | [MIT for source code](https://github.com/iScOp-uniovi/Paper_NACO_Barredo_2023/blob/b2f42bed2584022433dfe270612fe777ec8d1f58/LICENSE-MIT); datasets use the separately declared ODC-By terms |
| Platform classes | `org.workflowsim.planning.naco` DNC evaluator, `NacoGaHeftOptimizer`, `NacoGaOperators`, `NacoAdapter`, and `NacoGaHeftPlanningAlgorithm` |
| Verification | `NacoDncEvaluatorTest`, `NacoGaHeftOptimizerTest`, `NacoGaHeftIntegrationTest`; M37/M38 |
| Status | `VERIFIED_HYBRID_CORE` for the DNC + elite GA/HEFT + GPX/mutation/replacement subset |

The implementation evaluates compute, disk-read, disk-write, and cross-host communication time;
constructs a HEFT elite seed; generates topological chromosomes with compatible VM coordinates;
applies GPX crossover and dependency-bounded mutation through the `planning.naco.ga` named stream;
and uses tournament selection and elite replacement. A real LOCAL `SimulationSession` verifies the
planner. The two-VM hand oracle gives makespan 3, while a DNC case with communication and disk-read
gives finish time 8. These are semantic checks, not claims of reproducing every paper workload,
DNC accuracy percentage, or optimal makespan.

See [algorithm contracts](<../algorithms/CONTRACTS.md>), the [mutation gate](<../advanced/MUTATION_GATE.md>),
and the [V&V case ledger](<VV_CASE_LEDGER.md>).
