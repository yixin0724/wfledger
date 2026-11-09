# Algorithm Experiment Audit (R-EXP-01, 2026-11-09)

## Why experiments were necessary

Static inspection and small hand-derived examples cannot prove that every algorithm is correct. This
round adds real `SimulationRunner` experiments across DAG shapes, VM resources, file sizes, failure
modes and seeds. It tests invariants and metamorphic behavior instead of assuming that a successful
run or a plausible makespan proves correctness.

## Matrix

| Group | Scenarios | Algorithms | Seeds | Runs |
|---|---|---|---:|---:|
| SHARED | reverse-ID chain, fan-out/fan-in with duplicate OUTPUT, independent tasks, large files | SHARED_HEFT, Network-Aware HEFT, GreenHEFT, MOHEFT, IC-PCP | 7, 11 | 40 |
| LOCAL | same DAG/resource families | LOCAL_HEFT, DHEFT, NACO GA/HEFT | 7, 11 | 24 |
| Online | reverse-ID chain, fan-out/fan-in, independent tasks | CWS, Reshi-C, Reshi-M | 7, 11 | 18 |
| Special | carbon slack/pressure, DVFS tiers, CRCH failure, EBPSM staggered workflows | algorithm-specific configurations | 7 | 4 |

Additional cases double task work and check makespan/energy direction, and verify that IC-PCP rejects
an actually infeasible deadline. Every run checks successful completion, simulation-end bounds,
logical-completion tail, flat/tiered energy, peak power, runtime cost, and successful-parent/child
ordering. Residual thresholds are `<1e-6` in their native units.

## Experiment-only defects found and fixed

1. A failed parent was added to the received set and released its child before a successful retry.
   `WorkflowEngine` now admits only `Cloudlet.SUCCESS` into the dependency-release set.
2. Retry creation appended the retry parent while retaining the failed parent. It now replaces the
   failed parent in the child's parent list.
3. `WorkflowDatacenter.projectComputeExecutionLength` overwrote CRCH remaining MI with the original
   task length. CRCH retries now preserve the recovery MI in both execution and TaskOutcome timing.

`CrchIntegrationTest` verifies the real chain: a failed parent retries, the child does not start
before the successful retry finishes, and the recovery window is hand-derived. M66/M67 guard the
successful-parent gate and remaining-work preservation.

## Interpretation and limits

The matrix passes all listed invariants after the fixes. It does not prove universal optimality,
full paper-corpus reproduction, or absence of defects in every possible DAG/platform. Experiment
coverage should grow when a new failure mode, resource dimension or execution model is added.
