# Algorithm, Execution, and Metrics Semantic Contracts

This document specifies how algorithm results map onto simulated execution and evidence. The available tags and combinations are catalogued in the [algorithm catalog](<CATALOG.md>) and are not repeated here in full. What counts as reproduction here is implementing and verifying the core decision semantics under an explicit WorkflowSim model, not rebuilding the full hardware, network, or software behavior of the papers' target environments.

Tests are expected to derive expected values from independent arithmetic, external tables, or independent small models, rather than calling the production decision function under test to generate the answer. The test links below state the specific properties covered; they do not mean that every legal configuration has been exercised one by one, and still less do they constitute calibration against real platforms.


## Standard Execution and Workload

[SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>) and [SimulationRunner](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java>) jointly enforce:

- Standard runs use NONE clustering and SPACE_SHARED VMs; a Task's PE requirement must not exceed the PE count of the selected VM, and each VM has at most one Job dispatched at a time.
- Any planner other than INVALID must be paired with STATIC. STATIC without a planner is legal only under an explicit coherent online assignment configuration, in which case the assignment policy first determines the target VM and STATIC does not reselect the target.
- The platform preflights resources and places VMs onto hosts deterministically; plans schedule work on VM timelines and do not interpret host co-location as a calibrated cross-VM interference or migration model.
- Workflow inputs and arrival tables are declared before startup and released at their times during the run. Runs in the same JVM are serial; a static plan's time estimates must not be treated, unverified, as an event replay for arbitrary staggered arrivals.

[TaskExecutionModel](<../../simulator/src/main/java/org/workflowsim/utils/TaskExecutionModel.java>) distinguishes the raw single-PE length from the effective computation amount of the current attempt:

```text
L_eff(t,v) = L_raw(t)                         no task cost matrix
L_eff(t,v) = Math.round(matrixSeconds(t,v) * mips(v))  with a matrix
computeSeconds(t,v) = L_eff(t,v) / mips(v)
```

Once a matrix exists, it is the authoritative cost input: a missing coordinate must not fall back to the raw length, the rounded result must be positive, and MI×PE×1,000,000 must be representable as a signed long. Multiple PEs execute single-PE work in parallel; the total length must not be divided again by single-PE MIPS and taken as the wall-clock computation duration. The raw `lengthMi` is not overwritten; `effectiveExecutionLengthMi` describes the actual computation work of the current attempt.

Time-aware independent-task planning, PSO, LOCAL planning, and the corresponding execution use this transformation; SHARED_STORAGE planners reject matrices and use the shared-storage durations discussed below. The current identifier for `executionSemantics` is `WORK_CONSERVING_TASK_EXECUTION_V2`; different execution conventions cannot be turned into identical evidence by rewriting an old manifest.

The [cost matrix regression](<../../simulator/src/test/java/org/workflowsim/planning/PlanningCostMatrixRegressionTest.java>) covers costs ranked opposite to raw MI, rounding, missing coordinates, and cost-free controls; the [PE domain test](<../../simulator/src/test/java/org/workflowsim/planning/PlanningPeCompatibilityTest.java>) covers faster-but-incompatible VMs, an empty feasible region, and CPOP full-path compatibility.

## Static Ordering, Insertion, and Ties

Full static DAG planning outputs a mapping and planned start times; [StaticSchedulePlan](<../../simulator/src/main/java/org/workflowsim/scheduling/StaticSchedulePlan.java>) builds the per-VM Job sequences ordered by planned start time, ordering equal starts by Job ID. [StaticSchedulingAlgorithm](<../../simulator/src/main/java/org/workflowsim/scheduling/StaticSchedulingAlgorithm.java>) only releases a VM's next Job once it is already ready, and **does not enforce the absolute planned start instant**.

A Task chosen later can be inserted into a legal gap between previously reserved intervals, so a selection order is not the final execution order. A queue head that is not yet ready may block the Jobs behind it; ordering must not be dropped ad hoc to improve some algorithm's results. RANDOM, PSO, and the independent-task mappers have no complete plan; they keep only the mapping, and the engine handles dependency release.

Model-generated stage-in Jobs are not source logical Tasks; static dispatch handles such Jobs with the fallback slot on the lowest VM ID. Their 110 MI workload and the root-task release intervals affect absolute instants; the offset depends on VM MIPS and the kernel tick, not on one fixed number of seconds shared by all experiments. Compute completion events are additionally affected by the minimum event interval `tick` and the derived completion safety margin: a completion is never scheduled earlier than `submit + tick + tick/10` (0.11 s after submission at the 0.1 s default; FGCS-R3, 2026-11-24, replacing the former fixed 0.01 s margin), and the plan does not fully replay the kernel event queue.

SPACE_SHARED retains the CloudSim integer-MI scheduling convention used by the static planning–execution mirror. Coherent lifecycle evidence separately records model-derived Task windows and actual Job envelopes; when the integer-MI event quantization makes those windows differ, the lifecycle validator applies the declared per-VM one-MI time bound and preserves `exactJobTiming=false` rather than silently claiming exact equality. The bound is checked in both Java and Python auditors and is not a general tolerance for dependency violations or missing work.

Ties must be resolved by each policy's explicit rules: in ready-batch Min/Max-Min, task ties use the smaller Job ID and candidate VM ties the smaller VM ID; FCFS/MCT preserve the ready arrival order, and round robin preserves its cross-batch cursor. Static independent tasks establish order by Task ID; in static DAGs, equal priorities and equal candidate scores usually fall back to Task/VM IDs, with DLS/ETF having the further priority rules described below. Unstable set iteration order must not replace these rules.

The [shared-storage HEFT tests](<../../simulator/src/test/java/org/workflowsim/planning/SharedStorageHeftPlanningAlgorithmTest.java>) verify rank, PE exclusion, and gap insertion; the [DLS/ETF insertion boundary tests](<../../simulator/src/test/java/org/workflowsim/planning/SharedStorageDlsEtfSlotSemanticsAuditTest.java>) cover abutting intervals, tiny gaps, floating-point boundaries, and reservation changes, so that caching or interval merging does not change the scheduling semantics.

## Communication Estimation and Execution Models

The [HEFT/CPOP paper by Topcuoglu, Hariri, and Wu](<https://doi.org/10.1109/71.993206>) uses task computation costs and inter-processor communication costs to estimate schedules. The paper's edge-communication assumptions, the simulator's file system, the instants at which transfers occur, and the VM occupancy span are not the same thing. A paper-style HEFT prediction that carries communication terms must not be interpreted directly as the execution time of an arbitrary SHARED configuration.

### The Compute Envelope Model of SHARED

The SHARED_STORAGE planners require SHARED, legacy data movement, no failures/overheads, NONE clustering, and SPACE_SHARED. For each Task, the storage latencies of the actual inputs are accumulated first and then converted to MI in one step:

```text
inputSeconds(t) = sum(bytes(file) / 1e6 / storageRate)
duration(t,v) = (L_raw(t) + floor(mips(v) * inputSeconds(t))) / mips(v)
```

This is neither per-file separate flooring nor free communication. Parent–child dependencies are constrained through the engine's releases, and the input delay is added into the Job's execution envelope; this track has no point-to-point communication edges, paths, or shared-link contention. The computation durations and stage-in estimates are implemented in [SharedStorageDagPlanner](<../../simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java>). The fixed-endpoint model also models input delay inside the Job, but its attached admission parameters differ, so it cannot be used for these SHARED_STORAGE planners without adaptation.

The planning–execution zero-tolerance mirror of the five SHARED planners (HEFT/CPOP/DLS/ETF/PEFT) is locked end to end by the [shared-storage mirror tests](<../../simulator/src/test/java/org/workflowsim/planning/SharedStoragePlannerRuntimeMirrorIntegrationTest.java>): the task windows, VM assignments, and graph of a full run are completely bit-exact identical to those of an independent re-planning (2026-10-23, A3 authoritative close-out).

### The V1 Input Availability Estimation of LOCAL

LOCAL_HEFT/CPOP/PEFT require LOCAL, STATIC, no failures/overheads, and the V1 pre-execution family. Contention-free computation uses effective MI; file transfers use decimal MB/s: SOURCE→VM is bound by the destination endpoint's bandwidth, and VM→VM uses the smaller of the two endpoints' bandwidths.

- At the instant a Task's dependencies become ready, only replicas already available in the current partial plan are read; a transfer is spared only when the destination already has a visible replica.
- File latencies from the same parent are summed, and arrival is estimated from the parent's planned completion instant; different parent groups may overlap.
- External inputs of roots and non-roots alike start at the consuming Job's dependency-ready instant. Platform-level stage-in registration does not mean the file is already local on the target VM.
- Input readiness is the maximum of dependency readiness, each parent group's arrival, and external input arrival; a positive hold is subject to the minimum event interval.
- INPUT replicas become available when the full hold ends, and OUTPUT at the planned computation completion. Future replicas must not let a Task inserted into an earlier gap hit it early; inputs may arrive earlier than when the consumer actually starts computing.
- The VM reserves computation only, and input preparation may overlap the VM's busy intervals. This is a partial-plan estimate, not an event replay of the whole run.

The [input availability regression](<../../simulator/src/test/java/org/workflowsim/planning/LocalDataAvailabilityPlanningTest.java>) covers external root inputs, future replicas, usable gaps, and inputs arriving earlier than the computation start. File name/size conflicts can make some readily available inputs inapplicable, and the success of an ordinary online run cannot be used to infer that LOCAL planning is also legal.

When V1 endpoints or Fat-tree contention are enabled, **planning still has no contention**: at run time all parent groups start together at Job readiness, without retroactively accumulating transfer progress since the earlier parent completions. The solver uses max-min progressive filling constrained jointly by nominal caps and resource capacity, reclaiming and reallocating capacity at internal completion points; it is not a simple fixed `capacity/n`. Therefore the difference between contention-free V1 and contended V1 also includes the transfer-start rule. Fat-tree additionally imposes deterministic directed-path constraints, and the external SOURCE still bypasses the fabric; see the [topology and routing contract](<../advanced/FAT_TREE_DESIGN.md>) for details.

### Coherent Files, Storage, and Control Plane

coherent V2/V3 are independent per-file lifecycles, not a replacement of the LOCAL planners' estimates with an exact network replay. Control dependency readiness, target assignment, input visibility, CPU execution, and SOURCE commits must be interpreted separately.

- **V2 file dataflow**: LOCAL; inputs are prepared from the actually visible source replicas along the actual paths, copies for the same file and the same destination may be merged, and each file's visibility is observed independently; the external SOURCE aggregates explicitly without limit and bypasses the fabric.
- **V3 storage dataflow**: the attached host of the bounded SOURCE and its read/write/NIC capacities must be declared. LOCAL may read VM replicas; SHARED must first wait for the SOURCE commit, then use the destination cache or read from the SOURCE, and even a same-VM cache cannot bypass the commit.
- Every successful V3 output carries a write-back obligation, including unconsumed sinks and zero-byte outputs; CPU return does not wait for write-back, but simulation end waits for the obligations to complete. Failed attempts publish no readable output.
- Both families can optionally enable Fat-tree. The contention-free control keeps the same paths, lifecycles, and per-flow bottlenecks, removing only cross-flow sharing; it does not make transfers zero-cost.
- Without online assignment enabled, RANDOM/STATIC are required; the explicit `CONTROL_READY_ONLINE_ASSIGNMENT_V1` uses INVALID/STATIC, selects the target after control readiness from nominal input estimates and CPU reservations, and then prepares the inputs. A NOOP retry reuses the original logical assignment.

`dataflowPlan` records file identity and producer/consumer relationships, not STATIC's per-VM execution-order plan. Lifecycle certificates record sources, paths, visibility, and causality; they do not certify per-interval service areas or future congestion, and online action certification does not replay exact in-flight balances either. The Workbench online export requires a complete and matching lifecycle capture; Java OFF or truncated runs cannot masquerade as complete action certificates. The model does not implement TCP, packet loss/ECN, packet-level queues, or adaptive routing.

See the [V2 contract](<../advanced/COHERENT_DATAFLOW_CONTRACT.md>), the [V3 contract](<../advanced/STORAGE_DATAFLOW_CONTRACT.md>), and the [online assignment contract](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md>) for the specific fields and value domains. The corresponding [storage runtime tests](<../../simulator/src/test/java/org/workflowsim/data/v2/StorageDataflowRuntimeTest.java>) and [online evidence tests](<../../simulator/src/test/java/org/workflowsim/experiment/OnlineDataflowArtifactIntegrationTest.java>) check commit gating, output obligations, failure sources, and capture completeness.

## Priority and Cost Semantics

### HEFT, CPOP, DLS, and ETF

Let `w̄(t)` be the average model duration over the compatible VMs, and `c̄` the average communication estimate of the chosen model; in SHARED the communication term is 0, and the model duration includes its shared-storage input delay.

```text
r_u(t) = w̄(t) + max_child(c̄(t,child) + r_u(child))
r_d(entry) = 0
r_d(t) = max_parent(r_d(parent) + w̄(parent) + c̄(parent,t))
CPOP priority(t) = r_u(t) + r_d(t)
```

The `max_child` term of the exit task is 0. HEFT sorts by descending upward rank and then selects the insertion-based minimum EFT. CPOP selects the highest priority within the dependency-ready set; the critical path is determined along the actual edges that satisfy the rank recurrence, selecting one path rather than all equal-priority nodes, and is pinned to the VM with the smallest total model duration that supports the whole path.

SHARED_STORAGE_DLS maximizes `b-level − earliestInsertionStart` over ready Task–VM pairs, breaking ties by Task ID and then VM ID. SHARED_STORAGE_ETF minimizes the insertion start time, preferring the higher b-level on equal starts, and then Task/VM IDs. They retain the selection cores of [DLS](<https://doi.org/10.1109/71.207593>) and [ETF](<https://doi.org/10.1137/0218016>), but they do not thereby acquire the original papers' interconnect hardware, communication resources, or homogeneous-processor assumptions.

### PEFT's Successor-Cost Recurrence

For compatible VMs, the current PEFT uses successor costs rather than the current Task's own cost:

```text
OCT(exit,v) = 0
OCT(t,v) = max_child min_compatible_v' {
    OCT(child,v') + w(child,v') + c(t,child,v,v')
}
rank_o(t) = mean_compatible_v OCT(t,v)
OEFT(t,v) = insertionEFT(t,v) + OCT(t,v)
```

Each step selects, only from the ready-list whose parent tasks are all assigned, the Task with the largest `rank_o`, and then the compatible VM with the smallest OEFT; ties use the smaller Task/VM IDs. A child's average OCT being higher than its parent's is not an error, and the ready-list guarantees the assignment order.

In LOCAL, cross-VM communication is computed by the endpoint bandwidth model and same-VM communication is 0; OCT is a static look-ahead, does not use runtime replica discounts, and the actual insertion EFT is estimated separately from the partial plan's replica availability. Once SHARED drops the communication term, a compatible candidate's OCT no longer depends on the current VM and processor selection degenerates to EFT, but the task priorities may still differ; it must not be called network-aware PEFT.

The recurrence and its source page numbers are preserved unchanged in the [PEFT paper fixture provenance note](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>). The [independent recurrence counterexamples](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftSuccessorCostContractTest.java>) cover exits, successor costs, and rank reversals; the [primary-source fixture regression](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftPrimarySourcePaperTest.java>) verifies the plan and the actual Task/Job intervals using the verbatim matrix/OCT/selection order. The current machine contract identifier is `PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`; variants using own costs or exit means must not be renamed to the original PEFT.

### CaWoSched Fixed-Plan Time Shifting (R-PAPER-01, 2026-11-05)

[CaWoSchedPlanner](<../../simulator/src/main/java/org/workflowsim/planning/cawosched/CaWoSchedPlanner.java>)
implements the compute-only core of Schweisgut et al., ICPP 2025
([DOI](https://doi.org/10.1145/3754598.3754673)). It is a post-planning component, not a new
`Parameters.PlanningAlgorithm` label and not a change to ordinary STATIC absolute-time behavior.
`CaWoSchedAdapter` converts an existing SHARED HEFT trace into a fixed mapping/order and uses raw
compute length / VM MIPS plus declared VM power. The implementation preserves dependencies and
per-VM order, computes initial EST/LST and slack/pressure scores, chooses the highest-green-budget
interval start in the current feasible range, and otherwise uses EST. The complete horizon is
covered by contiguous windows, and carbon excess is independently integrated as
`Σ max(0, P_idle + P_dynamic(t) - G(t)) dt`. See [paper reproductions](<../experiments/PAPER_REPRODUCTIONS.md>)
for the verified example and excluded communication, ILP, Workbench, and timed-dispatch scope.
The generic `org.workflowsim.planning.timed` API additionally provides `TimedActivity`/`TimedPlan`,
`PlanObjectiveEvaluator`, and `PostPlanningOptimizer`. All 16 compute-only variants are exposed by
`CaWoSchedVariant`: W applies the official power weighting, R builds refined block intervals and
decrements their budgets, and LS accepts only strictly carbon-improving legal moves. The generic
`CarbonExcessEvaluator` independently integrates the same complete-horizon metric.
`CommunicationEnhancedPlan` adds explicitly declared link activities and per-edge durations for
cross-VM dependencies. `planExactSingleProcessor()` performs exact DP for up to six ordered
activities, validated against independent integer-start enumeration and a hand-derived six-activity
non-integral block. Its non-integral branch generates candidates from every block length up to the
activity count; the three-task cap remains specific to heuristic R refinement. The `CAWOSCHED_SLACK` and
`CAWOSCHED_PRESSURE` labels use the same SHARED HEFT base and enable absolute planned starts via
`StaticSchedulePlan.withStartTimeEnforcement()` and the deferral-aware `StaticSchedulingAlgorithm`;
other planners keep their previous order-only behavior. On flat-power fleets, the two CAWOSCHED
labels' `SimulationReport` budget accounting mirrors the timed objective: it integrates to the full
configured deadline and folds each active Job by its own VM's `P_max − P_idle`. Other planning labels
retain the legacy report convention (simulation-end horizon and first-VM dynamic delta), and the
separate tiered per-tier branch is unchanged.

### Independent Tasks and Mapping-Only Objectives

The independent-task mappers reject any parent/child edges; their availability is a tentative cumulative computational load, not a runtime queue replay. OLB selects by availability but still updates with the effective computation durations; MET ignores availability; MCT, Min/Max-Min, and Sufferage use completion estimates. Sufferage defines the loss as 0 when only one compatible VM exists, and round robin deliberately disregards costs. The naming follows the [independent-task mapping taxonomy](<https://doi.org/10.1006/jpdc.1999.1581>); it does not automatically confer offline semantics on online ready batches.

RANDOM samples, in input Task order, among the compatible VMs sorted by ID. PSO instead first fixes the coordinates by Task/VM ID and projects incompatible positions onto the nearest legal VM index, choosing the smaller ID on equal distance, with no additional sampling. See [PSOPlanningAlgorithm](<../../simulator/src/main/java/org/workflowsim/planning/PSOPlanningAlgorithm.java>) for its reference implementation and constants.

```text
PSO fitness = 0.8 * sum(effectiveSeconds(t,assignedVm) * assignedVmMips / 1000)
            + 0.2 * max_vm(sum_assigned_tasks effectiveSeconds(t,vm))
```

This objective ignores DAG edges and the network. Only in the matrix-free raw MI model is the cost term mathematically equal to `sum(L_raw)/1000` and independent of the mapping; floating-point rounding must be considered separately. With matrices, the cost can vary with the mapping. It is not actual cloud billing and is not guaranteed to outperform other baselines.


### CWS RankMin-RR Static-DAG Adaptation (R-PAPER-02, 2026-11-05)

`CWS_RANKMIN_RR` implements the static-DAG/idle-VM subset of Lehmann et al.'s CWS Rank(Min)
Round-Robin policy. `WorkflowDagValidator` assigns each Task a CWS rank as the longest descendant
PROCESS path, with exit tasks ranked 1. `BasicClustering` copies that rank to each Job. Ready Jobs
are ordered by descending rank, ascending real input bytes, and ascending Job ID. Real input uses
`FileItem.isRealInputFile`, so a same-named output inside the Job does not inflate the tie-break;
the maintained persistent RR then selects currently idle PE-compatible VMs. This is not HEFT upward rank and not
the dynamic Nextflow/Kubernetes API. The [Figure 1 integration test](<../../simulator/src/test/java/org/workflowsim/planning/CwsFigureOneIntegrationTest.java>)
uses the paper's exact physical DAG and verifies the five-to-four-unit scheduling direction.

### NACO GA/HEFT + DNC (R-PAPER-03, 2026-11-06)

`NACO_GA_HEFT` implements the NACO 2023 hybrid GA/HEFT core in the LOCAL controlled model.
`NacoAdapter` derives raw compute duration, external-input/output megabits, parent/child edge
megabits, VM network speed, and the declared storage rate from current WorkflowSim objects. File
bytes are converted by `8 / 1e6`; parent-produced input is charged on its dependency edge, not
again as external disk-read input. Compute coordinates and GA VM choices include only PE-compatible
VMs. Task PE count constrains placement but does not divide per-PE MI again: duration remains
`rawMI / vmMips`. `NacoDncEvaluator` computes `diskRead + communication + compute + diskWrite` after the earliest
feasible start, preserving parent completion and per-VM availability. HEFT ranks are evaluated in
reverse graph-topological order, not numeric Task-ID order. The GA uses a HEFT elite seed,
topological chromosomes, GPX crossover, dependency-bounded mutation, tournament selection, and elite
replacement under the `planning.naco.ga` named stream. Hand-derived evaluator and adapter oracles are
in `NacoDncEvaluatorTest` and `NacoAdapterTest`; the real LOCAL integration is in
`NacoGaHeftIntegrationTest`. This is not the full paper experiment pipeline or an
accuracy/optimal-makespan guarantee.

### Reshi-C/M with Explicit Rank and Prediction Inputs (R-PAPER-04, 2026-11-06)

`RESHI_C` implements Reshi-C's direct-child-count priority, and `RESHI_M` implements Reshi-M's
historical-average-runtime priority. Both use the explicit `ReshiRankTable` for VM selection over
idle PE-compatible VMs. A missing rank on every currently allocatable VM fails explicitly; no
first-fit fallback is allowed. The rank table and `ReshiTaskHistory` are experiment inputs, not
values inferred from production output. `SimulationConfig.reshiConfig` supplies them to
`SimulationSession`; missing inputs fail explicitly. `ReshiPredictionErrorModel` implements normal
or exponential multipliers with the official sign-and-clamp convention for prediction-consuming
baselines. Reshi-C/M themselves do not consume that model: their priority and named-stream state are
prediction-error invariant, and Reshi-M never samples inside its comparator. The implementation does
not train the paper's regression-tree recommender or replay AWS instance profiles.

### GreenHEFT Energy-First Static HEFT (R-PAPER-05, 2026-11-06)

`GREEN_HEFT` preserves HEFT upward-rank prioritization and changes VM selection to minimum
predicted task energy, with finish time and VM ID as deterministic tie-breaks. Candidate VMs are
PE-compatible, duration keeps the declared per-PE work convention `MI / per-PE MIPS`, and later tasks use earliest legal
reservation-gap insertion rather than append-only placement. `GreenHeftAdapter` requires one declared
plain power profile per VM and estimates task energy as full-load watts times raw-MI execution seconds.
Missing power, multiple frequency tiers, or a task-cost matrix fails explicitly. The implementation
is not the paper's empirical neural-network model or the MOHEFT Pareto optimizer.
`GreenHeftPlannerTest` distinguishes energy-first selection and gap insertion;
`GreenHeftAdapterTest` verifies PE compatibility and multi-PE duration.

### MOHEFT Pareto Beam Search (R-PAPER-06, 2026-11-06)

`MO_HEFT` expands HEFT-ordered task-to-VM candidates and keeps non-dominated makespan-energy
solutions. Candidates use the same PE filtering and reservation-gap insertion as GreenHEFT, so the
front does not lose feasible low-energy early-gap schedules. `MoHeftPlanner` retains both objective
extremes, applies crowding-distance truncation at beam width 8, and selects a balanced schedule by
normalized distance to the ideal point. A candidate is dominated only when another candidate is no
worse in both objectives and strictly better in at least one. The balanced tie-break is makespan,
energy, then deterministic candidate ID. `MoHeftPlannerTest` covers dominated-candidate removal,
Pareto extremes, balanced selection and gap-inserted candidates; `MoHeftIntegrationTest` covers a
real three-point tradeoff. This is not an exact full Pareto optimizer or an empirical neural-network model.

### IC-PCP Deadline/Cost Partial Critical Paths (R-PAPER-07, 2026-11-06)

`IC_PCP` implements one-phase IC-PCP: initial HEFT priority, EST/LFT, critical-parent partial
critical paths, whole-path VM trials over PE-compatible resources, and feasible
minimum-incremental-cost assignment. A virtual END node participates in the minimum slack check; an
infeasible deadline fails explicitly. The SHARED adapter starts root work after the model-generated
110-MI stage-in Job and root-release interval, and includes each task's shared-storage input seconds
using the same single integer-MI conversion and `envelopeMI / vmMips` duration as the SHARED
baseline. Its homogeneous communication
estimate converts declared decimal MB/s to bit/s consistently. Cost is computed per VM as
`ceil((lastFinish-firstStart)/billingPeriod) * billingPeriod * vmCostPerSecond`; the current adapter
uses a one-second billing period. No silent fallback to an arbitrary VM, dynamic provisioning,
IC-PCPD2, or full event-replay guarantee is made. `IcpcpPlannerTest` covers a low-cost chain,
infeasible deadline, billing-period arithmetic, fork/join and cycle rejection; `IcpcpAdapterTest`
covers units, PE filtering, duplicate declarations, SHARED input envelopes and absolute deadlines;
`IcpcpIntegrationTest` covers declared VM costs in a real session.

### Network-Aware HEFT Bottleneck Stretching (R-PAPER-08, 2026-11-06)

`NETWORK_HEFT` forms flows for cross-VM parent-child edges and stretches them on a shared
bottleneck. Active flows share the declared capacity, and each edge keeps an independent
`parentId→childId` duration key so multiple parents of one task cannot overwrite each other.
`NetworkHeftPlanner` applies insertion-based HEFT over PE-compatible candidates with the stretched
durations and keeps the best realistic schedule. The adapter converts decimal MB/s to bit/s for
nominal edge time and to the corresponding byte/s bottleneck capacity; duplicate OUTPUT declarations
are membership, not repeated payload. A zero-byte edge with a positive nominal duration derives its
work from the supplied capacity rather than a hidden rate. The six-task paper-style case yields
nominal 25 seconds and stretched 32 seconds; M45 kills removal of per-flow capacity sharing. The
current adapter uses one shared bottleneck and fair sharing, not the full TCP throughput model or
automatic path detection.

### EBPSM Budget and Cache Scheduling (R-PAPER-09, 2026-11-06)

`EBPSM` implements the public EBPSM resource-sharing policy rather than the closed MCPA alternative.
`EbpsmBudgetDistributor` applies DTL/EFT order and SFTD upgrades. `EbpsmSchedulingAlgorithm` follows
[Algorithm 2 of Hilman, Rodriguez and Buyya](<https://arxiv.org/abs/1903.01113>): ready tasks in ascending
estimated start, then PE-compatible idle VMs with the required input data, then with the deployed container
image, then any other idle compatible VM, each tier choosing the fastest VM whose predicted cost
`ceil(seconds) * vmCostPerSecond` fits the task's sub-budget. The prediction is
`read + effectiveCompute + write + container`: read/write follow paper Eqs. 1–2 with the declared global
storage rates and decimal-MB/s VM bandwidth (read is 0 on a VM caching every real input), compute is the
effective execution length of `TaskExecutionModel` on that VM, and the container term is the declared
provisioning delay unless the image is deployed.

Cache publication (FGCS-R3, 2026-11-24): `WorkflowScheduler` notifies `JobReturnListener` policies when an
attempt returns, after the failure model has fixed every Task status. For each **successful** Task, EBPSM adds
its container image and its real input and output files to the executing VM's local storage (paper Sec. 3.2
keeps `d_in` and `d_out`); a failed attempt publishes nothing, and nothing is published at dispatch. Local
storage is unbounded in this subset (no FIFO eviction).

Budget fallback (FGCS-R3, 2026-11-24): the paper's last resort when no idle VM fits is provisioning a new VM of
the fastest type within budget; the fixed declared pool cannot do that, so it is replaced by explicit rules
instead of a silent skip. A task stays queued while any VM is busy
after the round (a return is what frees a VM or grows a cache). If the round leaves an unaffordable task and an
entirely idle pool, or if even the best-case prediction (cached input, deployed container) exceeds the budget on
every compatible VM, the run fails with an `IllegalStateException`. The idle-pool rule is deliberately
conservative: it does not wait for a future workflow arrival or a delayed release that might warm a cache. These predictions are the policy's own estimate: the platform runtime
does not model EBPSM's VM-local cache and still charges the configured data-movement model (under SHARED, the
storage read on every attempt), so locality changes decisions, not simulated read time.

`EbpsmConfig` is injected through `SimulationConfig.ebpsmConfig`, so no hidden budget or cache source exists.
`EbpsmIntegrationTest` runs two staggered workflows and a parent→child chain in which the child is admitted
only on the VM holding the parent's published output; direct tests cover PE, bandwidth-budget, effective-length,
deferral, fail-fast, input retention and unsuccessful-output counterexamples. The subset uses a fixed declared
VM pool and does not claim Docker deployment, elastic provisioning, or full WaaS corpus reproduction.

### DVFS Slack Allocation and Tier Consumption (R-PAPER-10, 2026-11-08)

`DVFS_SLACK` starts from the maintained SHARED HEFT allocation and reservation order, including
root release, storage stage-in, PE compatibility and multi-PE duration. A downgrade is accepted only
when the complete schedule meets the deadline and total energy strictly decreases under the runtime
law: default-tier idle power over the whole makespan for every declared VM, plus each assigned tier's
dynamic power over its busy interval. Unused VMs and terminal idle time therefore participate in the
acceptance decision. `DvfsSlackRegistry` stores one tier per logical Task;
`DvfsSlackSchedulingAlgorithm` implements `FrequencyTierSelector` and looks up the logical Task inside
each Job, not the zero-based Job ID. The standard runner consumes the selected tier through the
existing admission-time DVFS mechanism. This is a greedy subset, not a global DVFS optimality or full
paper-corpus claim.

### CRCH Synchronized-Checkpoint Recovery (R-PAPER-11, 2026-11-08)

`CRCH` implements the checkpointing part of the 2019 CRCH paper. A checkpoint commits only after
its compute interval plus the configured checkpoint overhead fits in elapsed execution time; saved
work is capped at the original task length. Retry work is the remaining MI plus one recovery-overhead
MI equivalent. `ReclusteringEngine.process` registers the recovery for the retry Job, while
`CrchSchedulingAlgorithm` avoids the failed VM when an alternative exists. `FTCLUSTERING_CRCH` is an
explicit standard-runner mode; the historical DC/SR/DR/BLOCK/VERTICAL reclustering modes were removed on 2026-11-24.
The subset does not implement replication-count learning or PCA clustering. A failed parent never
releases its child; retry creation replaces the failed parent in the dependency list, and CRCH
remaining MI is preserved in execution and TaskOutcome timing. M49/M50 guard saved-work and
alternative-VM semantics; direct tests cover overhead-delayed checkpoints and capped saved work;
`CrchIntegrationTest` verifies a real failure, successful-parent ordering and remaining-work timing.

### DHEFT Ready-Task and Pairwise Bandwidth (R-PAPER-12, 2026-11-08)

`DHEFT` follows the official WorkflowSim DHEFT planner. The complete current ready wave is
scheduled in task-ID order before children unlocked by that wave enter the next wave. For every
PE-compatible VM, the start is `max(availableVM, parentFinish + bytes / (1e6 * min(bw_parent,bw_vm)))`
where the declared VM bandwidths are decimal MB/s; the direct problem API stores the converted rates
in bytes/s. The finish adds `rawMI / vmMips` under the maintained per-PE-work convention.
The earliest finisher wins with
deterministic VM-ID tie-breaking, and duplicate parent OUTPUT declarations count once per child INPUT.
This is a static ready-DAG subset, not a full dynamic-arrival workflow model. `DheftPlannerTest`
verifies cross-VM bandwidth, ready-wave and cycle cases; `DheftAdapterTest` verifies units, duplicate
membership and PE filtering; `DheftIntegrationTest` verifies LOCAL PlanningContext integration.
M51/M52 guard minimum-bandwidth and parent-finish rules.

## Metrics and Observation Samples

The metrics are computed by [SimulationMetrics](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationMetrics.java>), not by production-platform telemetry. All times are simulated seconds; wall-clock decision cost is reported separately.

### Waiting, Slowdown, and Missing Observations

For a compute Job attempt j that has a valid observation:

```text
wait_j = start_j - JOB_READY_j
execution_j = finish_j - start_j
slowdown_j = max((wait_j + execution_j) / max(execution_j, 10), 1)
meanComputeTrueSlowdown = arithmetic_mean(slowdown_j)
```

The two `max` operations are applied per attempt first and the mean only afterwards; this is not a single ratio taken over aggregated means, and there is no cap on the slowdown. `execution_j` is the Job's execution envelope: legacy/fixed-endpoint inputs may be included in it, while pre-execution/coherent input preparation happens before the start. `JOB_READY` and file-data readiness are not the same event, so waiting must not uniformly be interpreted as pure scheduling queuing.

- Total waiting and bounded slowdown are counted only for compute Jobs with a `JOB_READY` and `start >= ready`; missing or out-of-order observations do not enter the sample as zeros. Duplicated key events fail explicitly.
- The normal means include successful, failed, and retry attempts; `successOnlyMeanComputeTotalWaitingTimeSeconds` and `successOnlyMeanComputeTrueSlowdown` are separate success samples, not a renaming of the same statistic.
- When there are no observations, aggregate fields may be 0.0 by API convention; they must be combined with `totalWaitingTimeObservationCount`, `trueSlowdownObservationCount`, or `successOnlyWaitingObservationCount` to identify the missing measurements, and must not be reported as an observed zero wait.
- ready→decision and decision→start have their own observation counts; only when the sample sets are the same may the two means be added to obtain the total waiting.
- VM queue waiting is measured from VM submission; response is submission→finish and does not include the preceding ready waiting. A VM-level slowdown cannot substitute for this ready-based reference.
- Quantiles use the nearest rank `ceil(q*n)` on ascending samples; the median of an even-sized sample is not averaged between the two middle values, and P95 of a small sample can degenerate to the maximum. A bounded slowdown of 1 does not prove the absence of waiting.

The [waiting metric tests](<../../simulator/src/test/java/org/workflowsim/experiment/JobWaitingTimeMetricsTest.java>) cover hand-computed values, the 10-second floor, missing events, success samples, and quantiles. Event recomputation should filter compute Jobs (`classType == 2`) and must not mix stage-in ready events into that sample.

### Other Core Metrics

| Metric | Statistical conventions and boundaries |
| --- | --- |
| Job counts, success rate, and throughput | The total Job count may include stage-in; the compute-Job success rate/throughput uses compute attempts as samples, and failures and retries are not new logical Tasks |
| Logical task completion | A source Task counts as completed only with at least one successful compute attempt; the retry lineage must remain traceable to the failed parent attempt |
| `makespanSeconds` / `simulationEndSeconds` | The same simulation-end clock, not timed from the first compute Job |
| `logicalTaskCompletionSeconds` | When all source Tasks succeed, the maximum of each Task's first successful Job envelope completion time; unavailable when there are no logical tasks or completion has not occurred |
| `terminalLifecycleTailSeconds` | The difference between simulation end and logical completion for a completed workflow; V3 output obligations can form a tail and it must not be counted as CPU computation time |
| Retry and failure cost | Each returned attempt is counted separately; `retryAmplificationRatio` is logical-Task attempts / logical Tasks, not a failure probability |
| VM busy time and utilization | The union of completed Job intervals per VM, including applicable stage-in/failure/retry, divided by the simulation-end instant; not host utilization or network occupancy |
| Input demand and nominal transfer seconds | Includes external inputs of Jobs generated by the workflow's parent tasks, not only SOURCE; nominal estimates, actually copied bytes, and contended wall-clock time differ from one another and cannot substitute for one another |
| Abstract processing cost | The CPU envelope cost of each returned attempt plus the declared file bandwidth cost; bytes are converted as continuous decimal MB with no per-file billing rounding; declared memory/storage prices do not equal actual charges |
| Decision cost | `System.nanoTime` observations of explicit planning and scheduling cycles; they do not advance the simulation clock, and deterministic reruns are not required to be bit-exact |
| Controlled critical-path/SLR reference | Applies only within the declared SHARED, NONE, no-failure/overhead, SPACE_SHARED, legacy/fixed-endpoint scope; the pre-execution/coherent models with parallel inputs cannot reuse a serial-input lower bound; when unavailable, consult scope/available instead of treating zero as a valid lower bound |
| deadline SLA | Compares, from simulation time zero, the exact signed-long threshold against the binary64 end clock, performing the exact comparison first and computing the displayed slack afterwards; "not requested" and "workflow incomplete" must be distinguished, and dispatch, admission, retry, or termination are unchanged |
| Task times | Raw length and effective computation MI are kept separate; Task windows use the current attempt's workload, and exact Task/Job consistency additionally requires checking the timing scope and single-Task conditions |

The Jain index is `(sum(u))² / (n * sum(u²))`. Over the same window, the same VM sample, and a nonzero mean load, `Jain = 1 / (1 + CV²)`; the two are not independent evidence of distribution shape, and returning 1.0 for empty or all-zero utilization is a convention that does not mean the resources are well utilized.

## Evidence and Claim Scope

The failure model determines the outcome at the attempt envelope's completion boundary, and only successful outputs can become readable sources for later stages; this is controlled attempt/retry semantics, not mid-flight power loss, real distributed transactions, or storage-system calibration.

The algorithm contract, execution version, and model configuration in the manifest must be consistent with the actual inputs, platform, and evidence. Artifact structure/hash verification, lifecycle causality checks, algorithm oracles, statistical significance, and real-world calibration are distinct layers and cannot substitute for one another. Preserve the original evidence identity; do not mask differences through renaming, altering goldens, or mixing different decision/execution layers.
