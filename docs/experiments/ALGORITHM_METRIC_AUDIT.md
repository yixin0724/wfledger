# Algorithm and Metric Audit (R-AUDIT-01, 2026-11-08)

## Review method

Every reproduced algorithm family was reviewed through five linked layers: primary/official decision
semantics, adapter units and PE compatibility, task-to-VM mapping, real `SimulationRunner` execution,
and independent metric recomputation. Degenerate scenes that make all algorithms equal are not
accepted as evidence of equivalence. New counterexamples are hand-derived before the production fix.

The shared `AlgorithmMetricAudit` independently recomputes simulation-end bounds, logical-completion
tail, flat/tiered energy, and runtime processing cost from the declared cost model. All reproduced
families pass these runtime invariants in their legal scenes. Planner objectives remain distinct from
runtime accounting; IC-PCP planner cost is read directly from the planner schedule.

## Corrected defects

| Family | Corrected semantics and independent oracle |
|---|---|
| CaWoSched | Non-integral exact single-processor candidate generation; CaWoSched-only full-deadline heterogeneous flat-power carbon accounting, mirrored in Python |
| CWS | RankMin tie-break counts only `isRealInputFile` bytes |
| NACO | Decimal Mbit/s conversion, external versus parent-produced input separation, PE filtering, topology-based ranks |
| Reshi | PE filtering, missing-rank fail-fast, historical-average Reshi-M priority invariant to prediction error and comparator-safe |
| GreenHEFT | PE filtering and earliest legal reservation-gap insertion |
| MOHEFT | Gap-inserted Pareto candidates preserved; dominance and balanced selection retained |
| IC-PCP | Bit-rate conversion, PE filtering, duplicate-output membership, SHARED bootstrap/storage timing, billing-period cost |
| EBPSM | PE filtering, decimal-MB/s transfer estimates, successful-only output publication |
| DVFS slack | SHARED HEFT allocation/timing, full-fleet default-idle plus tier-dynamic energy, unused-VM and terminal-idle inclusion |
| CRCH | Checkpoint `k` commits `k·(λ+γ)` into compute, overhead MI on every attempt, VM-local saved work (FGCS-R5) |
| DHEFT | Matrix rejection, decimal-MB/s conversion, PE filtering, official ready-list waves, duplicate-output membership |
| EDP heuristic | Default idle charged only outside the local execution interval |
| Tiered energy | Peak power uses the maximum declared tier power, not necessarily the default tier |
| Runtime cost | `CostModel.VM` is explicit in cross-paper comparisons; planner cost and runtime Job-envelope cost are separate fields |

## Metric invariants

- `makespanSeconds` is the simulation-end clock and is not replaced by the last compute Job.
- `terminalLifecycleTailSeconds = makespanSeconds - logicalTaskCompletionSeconds` when available.
- Flat energy is `P_idle × makespan + (P_max - P_idle) × busy` per VM; tiered energy uses default-tier
  idle over the full makespan plus each assigned tier's dynamic busy power.
- Runtime cost is CPU-envelope cost plus declared-file bandwidth cost under the configured
  DATACENTER or VM cost model; it is not a provider invoice or an IC-PCP path-window cost.
- SHARED/LOCAL physical models and planner/runtime objective surfaces are never merged into one
  population or silently compared as the same metric.

## Regression and mutation coverage

`AlgorithmMetricAuditTest` runs SHARED/LOCAL baselines, GreenHEFT, MOHEFT, IC-PCP,
DHEFT, NACO, CaWoSched, CWS, Reshi-C/M, EBPSM, CRCH and DVFS. M54–M65 add targeted mutants
for the newly corrected mechanisms; M49 was re-anchored to the revised CRCH saved-work expression.
The full Maven/Python/documentation/language/dataset gate and the complete registered mutation gate
are required before close-out.

This audit does not claim exact paper-table reproduction, full corpus coverage, global optimality,
or real-platform calibration.
