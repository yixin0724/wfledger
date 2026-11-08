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

## Reshi — IPCCC 2022

| Item | Record |
|---|---|
| Paper | Jonathan Bader, Fabian Lehmann, Alexander Groth, Lauritz Thamsen, Dominik Scheinert, Jonathan Will, Ulf Leser, Odej Kao, *Reshi: Recommending Resources for Scientific Workflow Tasks on Heterogeneous Infrastructures*, IPCCC 2022 |
| Primary source | [DOI](https://doi.org/10.1109/IPCCC55026.2022.9894299), [arXiv:2208.07905v2](https://arxiv.org/html/2208.07905v2) |
| Official artifact | [CRC-FONDA/WorkSim-PredError](https://github.com/CRC-FONDA/WorkSim-PredError), commit [`9fbcdc63410dd5550439612c459fc6d03ad5e62d`](https://github.com/CRC-FONDA/WorkSim-PredError/commit/9fbcdc63410dd5550439612c459fc6d03ad5e62d) |
| License | LGPL-3.0 (`license.txt` in the artifact) |
| Platform classes | `ReshiSchedulingAlgorithm`, `ReshiRankTable`, `ReshiTaskHistory`, `ReshiPredictionErrorModel`, and `ReshiRegistry` |
| Verification | `ReshiSchedulingAlgorithmTest`, `ReshiIntegrationTest`, `ReshiPredictionErrorModelTest`; M39 |
| Status | `VERIFIED_RANKED_SUBSET` for Reshi-C/M with explicit rank/history inputs and optional prediction errors |

Reshi-C prioritizes Jobs by direct-child count; Reshi-M prioritizes by historical average runtime.
Both use an explicit task-node rank table for VM selection. Reshi-C/M require `SimulationConfig.reshiConfig`,
so missing rank/history inputs fail explicitly. Reshi-M can additionally apply normal or exponential
prediction errors through a named random stream, including the official sign-and-clamp convention.
No regression-tree training, AWS image-type profiling, or complete production trace pipeline is
built in; these are adaptation boundaries, not claims of reproducing the paper's full recommender.

## GreenHEFT — FGCS 2014

| Item | Record |
|---|---|
| Paper | Juan J. Durillo, Vlad Nae, Radu Prodan, *Multi-objective energy-efficient workflow scheduling using list-based heuristics*, Future Generation Computer Systems 36, 221–236, 2014 |
| Primary source | [DOI](https://doi.org/10.1016/j.future.2013.07.005), [open repository record](http://resolver.obvsg.at/urn:nbn:at:at-ubi:3-2421) |
| Reference artifact | [Dongyubo66/ecds-workflow-scheduling](https://github.com/Dongyubo66/ecds-workflow-scheduling), GreenHEFT baseline, commit [`36ecaf401a81e0fed5c6447853bc7054fea22bfc`](https://github.com/Dongyubo66/ecds-workflow-scheduling/commit/36ecaf401a81e0fed5c6447853bc7054fea22bfc) |
| License | MIT for the third-party reference artifact; the paper remains the primary definition. No original-author GreenHEFT source repository was found |
| Platform classes | `org.workflowsim.planning.greenheft` and `GreenHeftPlanningAlgorithm` |
| Verification | `GreenHeftPlannerTest`, `GreenHeftIntegrationTest`; M40 |
| Status | `VERIFIED_ENERGY_CORE` for static HEFT-priority, minimum-energy VM selection with finish-time tie-break |

The implementation keeps HEFT upward-rank prioritization and chooses the VM with the lowest
predicted task energy, using finish time and VM ID only as tie-breaks. The current adapter estimates
energy as declared full-load watts times raw-MI execution seconds; missing power or multiple
frequency tiers fails explicitly. This is not the paper's trained empirical power/performance model,
MOHEFT Pareto front, or a claim of reproducing its full experimental corpus.

## MOHEFT — FGCS 2014 / CCGrid 2013

| Item | Record |
|---|---|
| Paper | Juan J. Durillo, Vlad Nae, Radu Prodan, *Multi-objective energy-efficient workflow scheduling using list-based heuristics*, Future Generation Computer Systems 36, 221–236, 2014 |
| Primary sources | [FGCS DOI](https://doi.org/10.1016/j.future.2013.07.005); earlier [CCGrid 2013 DOI](https://doi.org/10.1109/CCGrid.2013.62) |
| Reference artifacts | [Dongyubo66/ecds-workflow-scheduling](https://github.com/Dongyubo66/ecds-workflow-scheduling) commit `36ecaf4`; [leimiaomiao/Multi-Objective-Workflow-Scheduling](https://github.com/leimiaomiao/Multi-Objective-Workflow-Scheduling) commit `280b745` |
| License | ECDS reference is MIT; the second reference has no license file and is used for inspection only, not copied or redistributed |
| Platform classes | `org.workflowsim.planning.moheft` and `MoHeftPlanningAlgorithm` |
| Verification | `MoHeftPlannerTest`, `MoHeftIntegrationTest`; M41/M42 |
| Status | `VERIFIED_PARETO_CORE` for HEFT-ordered beam search, non-dominated filtering, crowding and balanced selection |

MOHEFT expands task-to-VM candidates in HEFT order, removes dominated candidates, retains makespan
and energy extremes with crowding-distance truncation, and selects a deterministic balanced schedule
using normalized distance to the ideal point. This is a beam-search Pareto subset, not an exact full
Pareto-front optimizer or the paper's empirical neural-network model. The reference implementation
semantics were inspected independently; no third-party code was copied without a compatible license.

## IC-PCP — FGCS 2013

| Item | Record |
|---|---|
| Paper | Saeid Abrishami, Mahmoud Naghibzadeh, Dick H. J. Epema, *Deadline-constrained workflow scheduling algorithms for Infrastructure as a Service Clouds*, Future Generation Computer Systems 29(1), 158–169, 2013 |
| Primary source | [DOI](https://doi.org/10.1016/j.future.2012.05.004), [Semantic Scholar record](https://www.semanticscholar.org/paper/963fffd1e48466f767cc760c16069356fdbfc126) |
| Reference artifact | [ralthor/Scheduler](https://github.com/ralthor/Scheduler), `Scheduler/ICPCP.py`, commit [`d8f8963a021abe945b8cebee693341031d086e77`](https://github.com/ralthor/Scheduler/commit/d8f8963a021abe945b8cebee693341031d086e77) |
| License | No license file was found in the reference repository; inspected for algorithm semantics only, not copied or redistributed |
| Platform classes | `org.workflowsim.planning.icpcp` and `IcpcpPlanningAlgorithm` |
| Verification | `IcpcpPlannerTest`, `IcpcpIntegrationTest`; M43/M44 |
| Status | `VERIFIED_DEADLINE_COST_CORE` for one-phase PCP, EST/LFT, path-cost and deadline-feasible VM selection |

The planner constructs critical-parent partial critical paths using `lft-est`, tries each whole path on
each VM, and selects the feasible candidate with minimum incremental cost and maximum flexibility.
The virtual END node participates in slack calculation, so an infeasible deadline fails explicitly
rather than falling back to an arbitrary VM. The current adapter uses raw MI, homogeneous estimated
network transfer time, VM cost per second, and a one-second billing period. This is not IC-PCPD2,
dynamic VM provisioning, or a full paper-corpus reproduction.

## Network-Aware HEFT — The Scientific World Journal 2014

| Item | Record |
|---|---|
| Paper | Muhammad Murtaza Yousaf, Michael Welzl, *Network-Aware HEFT Scheduling for Grid*, The Scientific World Journal, 2014, Article 317284 |
| Primary source | [DOI](https://doi.org/10.1155/2014/317284), [PMC full text](https://pmc.ncbi.nlm.nih.gov/articles/PMC3919093/) |
| Platform classes | `org.workflowsim.planning.networkheft` and `NetworkHeftPlanningAlgorithm` |
| Verification | `NetworkHeftPlannerTest`, `NetworkHeftIntegrationTest`; M45 |
| Status | `VERIFIED_BOTTLENECK_CORE` for fair-shared bottleneck flow stretching and iterative HEFT rescheduling |

The implementation forms cross-VM flows, orders them by start time, shares bottleneck capacity among
active flows, advances to the next arrival or completion, and reschedules HEFT with stretched edge
durations. The paper-style six-task case reproduces the nominal 25-second schedule and the stretched
32-second result. The current adapter uses one shared bottleneck per experiment and fair capacity
sharing; it does not claim the full TCP throughput model, path-detection system, or complete Grid
workflow corpus.

## EBPSM — Multi-tenant WaaS Resource Sharing (2020)

| Item | Record |
|---|---|
| Paper | Muhammad H. Hilman, Maria A. Rodriguez, Rajkumar Buyya, *Resource-sharing Policy in Multi-tenant Scientific Workflow-as-a-Service Cloud Platform*, arXiv 2020 |
| Primary source | [arXiv:1903.01113v3](https://arxiv.org/html/1903.01113v3), [arXiv DOI](https://doi.org/10.48550/arXiv.1903.01113) |
| Platform classes | `EbpsmSchedulingAlgorithm`, `EbpsmBudgetDistributor`, `EbpsmConfig`, `EbpsmRegistry` |
| Verification | `EbpsmBudgetDistributorTest`, `EbpsmSchedulingAlgorithmTest`, `EbpsmIntegrationTest`; M46/M47 |
| Status | `VERIFIED_BUDGET_CACHE_CORE` for SFTD budget distribution and cache/budget-gated scheduling |

EBPSM preprocesses task budgets using DTL/EFT order and slowest-first upgrades, then dispatches ready
Jobs by input-data cache, container-image cache, and other idle VMs in that order. Each candidate
must fit the task's sub-budget; over-budget candidates remain unscheduled. Two workflows with staggered
arrivals complete through the standard runner. The current subset uses a fixed declared VM pool and
explicit cache declarations, not Docker deployment, elastic VM provisioning, or a full WaaS corpus.
The closed MCPA alternative was not reproduced or mislabeled.

## DVFS Frequency Scaling — ICPPW 2014

| Item | Record |
|---|---|
| Paper | Ilia Pietri, Rizos Sakellariou, *Energy-Aware Workflow Scheduling Using Frequency Scaling*, 43rd International Conference on Parallel Processing Workshops, 2014 |
| Primary source | [DOI](https://doi.org/10.1109/ICPPW.2014.26), [paper abstract](https://dl.acm.org/doi/10.1109/ICPPW.2014.26) |
| Platform classes | `org.workflowsim.planning.dvfs`, `DvfsSlackPlanningAlgorithm`, `DvfsSlackSchedulingAlgorithm`, and `DvfsSlackRegistry` |
| Verification | `DvfsSlackOptimizerTest`, `DvfsSlackIntegrationTest`; M48 |
| Status | `VERIFIED_SLACK_CORE` for HEFT mapping plus iterative energy-decreasing DVFS slack allocation |

The planner starts from a HEFT allocation and repeatedly tries lower tiers only when the complete
schedule remains within the deadline and total energy decreases. It includes busy and idle power
in the comparison, not just task energy. The scheduler consumes the planner's per-task tier through
`FrequencyTierSelector`, and the full runner verifies every COMPUTE Job receives the selected tier.
This is a greedy slack-allocation subset, not a proof of the full paper algorithm, global DVFS
optimality, or a reproduction of its synthetic workload corpus.

## CRCH — Replication Heuristics and Checkpointing (2019)

| Item | Record |
|---|---|
| Paper | Amrith Rajagopal Setlur, S. Jaya Nirmala, Har Simrat Singh, Sudhanshu Khoriya, *An Efficient Fault Tolerant Workflow Scheduling Approach using Replication Heuristics and Checkpointing in the Cloud*, Journal of Parallel and Distributed Computing, 2019 |
| Primary source | [arXiv:1810.06361v2](https://arxiv.org/html/1810.06361v2), [publisher record](https://www.sciencedirect.com/science/article/abs/pii/S0743731518306580) |
| License | CC BY 4.0 |
| Platform classes | `CrchRegistry`, `CrchRecovery`, `CrchSchedulingAlgorithm`, and the `FTCLUSTERING_CRCH` retry path |
| Verification | `CrchRegistryTest`, `CrchSchedulingAlgorithmTest`, `CrchIntegrationTest`; M49/M50 |
| Status | `VERIFIED_RECOVERY_CORE` for synchronized-checkpoint remaining-work retry and alternative-VM selection |

The implementation records completed synchronized checkpoints in session state, computes the remaining
MI from the latest checkpoint, registers a recovery result at `ReclusteringEngine.process`, and chooses
an alternative VM for the retry. A real FAILURE_VM run completes with one failed VM and one stable VM.
The checkpoint-only subset does not implement replication-count learning, PCA clustering, or the full
CRCH replication heuristic.

See [algorithm contracts](<../algorithms/CONTRACTS.md>), the [mutation gate](<../advanced/MUTATION_GATE.md>),
and the [V&V case ledger](<VV_CASE_LEDGER.md>).
