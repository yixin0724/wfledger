# Mutation Gate

The mutation gate checks that the test battery still fails when a registered semantic mutation is
applied. It is a canary battery over the decision surfaces that carry research claims, one canary
per axis, not an exhaustive mutation score. The registry lives in code, `REGISTRY` in
[scripts/mutation-gate.py](<../../scripts/mutation-gate.py>); this page lists the registered
canaries and documents usage and the addition policy.

## Registered mutant ledger (scope)

| ID | Semantic break injected | Axis guarded |
|---|---|---|
| M1 | HEFT upward rank takes the first child instead of the maximum | planning rank computation |
| M2 | MCT drops the availability term | static independent-task cost model |
| M3 | Dependency gate inverted (only unsatisfied jobs pass) | engine dependency gating |
| M4 | Fat-tree aggregation index picks `halfK` instead of `availableAggregateCount` | topology routing determinism |
| M5 | Fluid completion tolerance magnified 1000x | numerical tolerance boundary |
| M6 | MI conversion `floor` → `round` at the parser boundary | input quantization semantics |
| M8 | Online assignment halves the compute estimate | coherent binding nominal score |
| M9 | Task execution window offset by 1e-6 s | timing-window exactness |
| M10 | SHARED DLS dynamic level flips the earliest-start subtraction into an addition | DLS decision surface |
| M11 | Registered declaration's ANY scope covers no file system | registry declaration semantics |
| M12 | Engine fork drops the shared retained-trace prefix | contention trace fork conservation |
| M13 | Metric recompute mismatch check ignores the declared metrics value | artifact validator recompute |
| M14 | Holm step-down drops the `(familySize - rank)` multiplier | within-family multiplicity correction |
| M15 | Event recorder cap admits one event beyond the budget | recorder retention fail-fast |
| M16 | Cost-caliber guard rejects homogeneous groups instead of mixed ones | comparison-group caliber homogeneity |
| M18 | Finished lifecycle validator still accepts event feeding | lifecycle single-use contract |
| M19 | INVALID label binds the FCFS policy instead of the static no-decision shell | unmapped label explicit binding |
| M20 | Runner ignores the configured event-recorder cap and constructs the default recorder | cap configuration outlet |
| M21 | Declared revision contract ignored, so a marker-less stream falls back to the legacy boundary | declared `events.revisionContract` requirement |
| M22 | Campaign validator skips the independent statistical-summary recompute | campaign summary tamper detection |
| M23 | WfCommons parser skips schema-version and unknown-field validation | WfFormat 1.5/1.6 input gate |
| M24 | Space-Shared drops the file-transfer MI added to Cloudlet length | CloudSim 3.0.3 transfer-work accounting |
| M25 | FutureQueue assigns serial zero to every event | same-time event ordering |
| M26 | WorkflowDatacenter drops the SHARED input-transfer seconds | legacy WorkflowSim transfer envelope |
| M27 | WfCommons runtime conversion collapses every task to the minimum length | Montage real-derived runtime mapping |
| M28 | Exact Wilcoxon reverts to the Commons Math 3.2 rank-sum path that discards ties | actual-rank exact statistical verification |
| M29 | Completion guard ignores the configured minimum event interval | tick semantics |
| M30 | Workbench drops the event-recorder cap from the simulation allowlist | experiments configuration surface |
| M31 | Coherent dataflow runtime reports zero admitted copies | profile semantic-fingerprint and copy-count verification |
| M32 | CaWoSched green-window selection degrades to ASAP | paper algorithm reproduction |
| M33 | CaWoSched power weighting is removed from slack scores | generic timed-plan variants |
| M34 | Communication-enhanced DAG silently drops a missing link | communication model |
| M35 | Exact single-processor DP drops all non-zero candidate starts | exact oracle |
| M36 | CWS RankMin comparator ignores DAG rank | CWS static-DAG online scheduling |
| M37 | NACO DNC removes the cross-host communication term | NACO DNC evaluation |
| M38 | NACO GPX discards the first-parent prefix | NACO hybrid GA operator |
| M39 | Reshi prediction error drops the non-negative runtime clamp | Reshi prediction-error model |
| M40 | GreenHEFT selects earliest finish instead of minimum energy | GreenHEFT energy-first VM selection |
| M41 | MOHEFT dominance ignores energy | MOHEFT Pareto filtering |
| M42 | MOHEFT balanced choice ignores makespan tradeoff | MOHEFT balanced selection |
| M43 | IC-PCP ignores the latest-finish-time feasibility of assigned tasks | IC-PCP deadline feasibility |
| M44 | IC-PCP path choice ignores incremental cost | IC-PCP path-cost selection |
| M46 | EBPSM ignores input-data cache preference | EBPSM cache preference |
| M47 | EBPSM dispatches tasks above their budget | EBPSM budget gate |
| M48 | DVFS slack allocation accepts an energy-increasing downgrade | DVFS slack optimization |
| M49 | CRCH recovery ignores completed checkpoint work | CRCH saved-work recovery |
| M50 | CRCH credits saved work on a VM that did not take the checkpoints | CRCH VM-local checkpoints |
| M51 | DHEFT uses maximum instead of minimum pairwise bandwidth | DHEFT pairwise bandwidth |
| M52 | DHEFT ignores parent finish time | DHEFT dependency readiness |
| M53 | Cross-paper comparison drops direct IC-PCP planner cost | Planner/runtime cost separation |
| M54 | DVFS slack drops unused-VM and terminal idle energy | Full-fleet DVFS energy law |
| M55 | DVFS slack drops SHARED stage-in and root release timing | DVFS SHARED deadline/timing |
| M56 | GreenHEFT reverts to append-only placement | GreenHEFT gap insertion |
| M57 | GreenHEFT admits PE-incompatible VMs | GreenHEFT PE compatibility |
| M58 | DHEFT drops decimal-megabyte bandwidth conversion | DHEFT bandwidth units |
| M60 | Reshi-M reapplies prediction noise inside its comparator | Reshi-M prediction invariance |
| M61 | CWS counts non-real input bytes in its tie-break | CWS real-input ordering |
| M62 | NACO double-counts parent-produced input as external disk read | NACO DNC input accounting |
| M63 | EBPSM publishes unsuccessful output as readable cache | EBPSM successful-only publication |
| M64 | CRCH ignores checkpoint overhead when committing progress | CRCH checkpoint commit timing |
| M65 | EDP heuristic depends on the dispatch clock | Clock-invariant local EDP |
| M66 | Failed parent releases its child before a successful retry | Successful-parent dependency gate |
| M67 | CRCH retry overwrites remaining work with the original task length | CRCH remaining-work preservation |
| M68 | Tiered retry restarts from the already scaled length | Declared-work retry restart |
| M69 | CRCH credits the input read as checkpointed compute | CRCH compute-window checkpoints |
| M70 | CRCH placement ignores PE compatibility | CRCH PE compatibility |
| M71 | EBPSM budget update ignores the completed task's spare budget and debt | EBPSM Algorithm 3 budget update |
| M72 | CRCH checkpointing is free on a successful attempt | CRCH per-attempt checkpoint overhead |
| M73 | A failed attempt runs to its modeled completion instead of ending at the failure | Mid-execution failure |
| M74 | CRCH placement ignores the saved work on the checkpointing VM | CRCH same- versus different-VM placement |
| M75 | CRCH resumption charges a restore overhead beyond the lost work | CRCH no restore term |
| M76 | Online Min-Min/Max-Min/MCT ignore the machine ready time of busy VMs | Machine ready time `r_v` in `CT = r_v + ETC` |
| M77 | Online Min-Min/Max-Min/MCT do not update `r_v` after an assignment | Braun et al. (2001) ready-time update step |
| M78 | Late binding dispatches a Job that is not the first plan of its idle VM | Late binding: one first plan per idle VM |
| M79 | Late binding dispatches a Job planned behind a busy VM | Late binding: busy-VM plans stay queued |

M7, M17, M45, and M59 are retired because their semantic axes were removed. M30 and M53 belong to
the experiments family; all others belong to the simulator family.

## Usage

```bash
# Simulator family (about 5–10 minutes; requires a clean Git tree and an exclusive run)
python3 -B scripts/mutation-gate.py --output <new-report.json>

# Experiments family (same clean/exclusive requirement)
python3 -B scripts/mutation-gate.py --module experiments --output <new-experiments-report.json>

# Both families sequentially
python3 -B scripts/mutation-gate.py --all-modules --output <new-report.json>

# Ledger + anchor liveness only (no mutation, no Maven; cheap periodic preflight)
python3 -B scripts/mutation-gate.py --output <new-report.json> --dry-run

# Subset by id
python3 -B scripts/mutation-gate.py --output <new-report.json> --ids M5,M9

# Convenience target
make mutation-gate

# Self-tests (no Maven involved)
python3 -B scripts/test-mutation-gate.py
```

Exit codes: **0** all registered mutants KILLED; **1** a mutant SURVIVED or the build
infrastructure failed while the mutant was applied; **2** at least one registered anchor is
stale (source drifted; the ledger must be updated first). The report is JSON, written only
to a path that does not yet exist (never-overwrite semantics, same discipline as the
independent auditors).

CI runs both registered families on every push and pull request in the dedicated `mutation-gate`
job ([ci.yml](<../../.github/workflows/ci.yml>)). The job's clean checkout satisfies the clean-tree precondition, the dedicated runner
satisfies the exclusive-run rule, and both JSON reports are uploaded as build artifacts. Local
runs remain the entry point for ledger work and the `--dry-run` anchor preflight.

## Execution model and safety

- Each mutant is applied **in place** to the working tree; every target file is restored
  from an in-memory backup in a `finally` block and re-verified byte-equal after restore.
- Full mode refuses to start when `git status` is dirty.
- Per mutant, the registered **killer test set** runs first (fast); if it does not kill, the
  corresponding full module battery runs as escalation (`:workflowsim`, or
  `:workflowsim-workbench -am` for the experiments family). KILLED is claimed only on Maven exit
  non-zero **with** test failures; a non-zero exit without failures is an INFRA_FAILURE,
  not a kill.
- Anchor uniqueness: each registered anchor must occur **exactly once** in the current tree.
  Style-only refactors therefore make an anchor stale — the gate exits 2 and points at the
  mutant id; update the anchor to the current text (drift repair is not a semantic change).
- Interruption recovery: the clean-tree precondition makes recovery deterministic — if the
  run is killed mid-mutant, the single dirty file left behind **is** the applied mutant;
  restore with `git checkout -- <file>` and re-run in the foreground. Do not run this gate
  as a killable background job.

## Adding a mutant (ledger policy)

1. Register in `REGISTRY` with: unique `M<n>` id, rationale-bearing `name`, target `file`,
   exact `old`/`new` anchor (unique in tree), and the killer test classes.
2. Demonstrate the kill **red-first**: run the gate for that id alone and record KILLED.
3. Extend this page's ledger table with the guarded axis.
4. If the anchor is later re-shaped by mechanical refactors, repair the anchor without
   changing the mutant's semantics; the dry-run preflight makes drift cheap to detect.

Adding a mutant to fix a *survived* canary means a gap was found: first write the failing
killer test (expectation from literature/contract/conservation law, per the repo's
three-source rule), then register the mutant that it kills.
