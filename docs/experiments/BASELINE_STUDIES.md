# Baseline Studies

The repository ships Workbench configurations and Java drivers for two baseline studies on
classic Pegasus DAX workflows: makespan and energy. This page describes what each study
runs and how to run it. Results are not published here; generate them with the current code into
a new directory.

## Common platform

All configurations use the same declared platform: four SPACE_SHARED VMs at 500, 1000, 1500, and
2000 MIPS (one PE each), shared storage at 15 MB/s, VM bandwidth 100 MB/s, the SHARED file system
with `LEGACY_WORKFLOWSIM_V1` data movement, `CostModel.VM` with CPU prices 1, 2, 4, and 8 per
second, `runtimeReferenceMips=1000`, seed 7, and an event-recorder cap of 200000. VM power is
idle 5/10/15/20 W and max 50/100/150/200 W unless a tier table replaces it.

## Studies

| Configuration | Inputs | Algorithms | Decision layer |
|---|---|---|---|
| [makespan-static-baseline.json](<../../experiments/configs/makespan-static-baseline.json>) | Montage 50/100, CyberShake 50/100, SIPHT 60, Inspiral 50, Epigenomics 46 | `SHARED_STORAGE_HEFT` | Static planning |
| [makespan-online-baseline.json](<../../experiments/configs/makespan-online-baseline.json>) | Same seven DAX files | `READY_BATCH_MINMIN`, `READY_BATCH_MAXMIN`, `READY_BATCH_MCT`, `FCFS`, `READY_BATCH_ROUNDROBIN` | Online scheduling |
| [energy-flat.json](<../../experiments/configs/energy-flat.json>) | Montage 50/100, CyberShake 50, SIPHT 60, Inspiral 50 | `SHARED_STORAGE_HEFT`, `GREEN_HEFT`, `MO_HEFT` | Static planning, flat power |
| [energy-tiered.json](<../../experiments/configs/energy-tiered.json>) | Same five DAX files | `SHARED_STORAGE_HEFT` with `DEFAULT`, `MIN_POWER`, `EDP_HEURISTIC` frequency policies, and `DVFS_SLACK` | Static planning, two-tier power per VM, per-workflow deadlines |

Static planning and online scheduling use separate configurations because they are different
decision layers; flat and tiered power are separate populations. Do not merge them into one
ranking.

## Running

From the repository root with JDK 17+ and Maven 3.6.3+:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/makespan-static-baseline.json output/<new-directory>"
```

Substitute any configuration above. Each output directory contains `summary.html`,
`summary.json`, `summary.csv`, the detailed `report.html`, and one evidence bundle per run. The
summaries report makespan, waiting time, slowdown, throughput, utilization, energy, EDP, cost, and
completion counts. See the [Workbench guide](<../getting-started/WORKBENCH.md>).

Equivalent Java drivers write a single result JSON (default path in parentheses):

| Driver | Test | Default output |
|---|---|---|
| `org.workflowsim.experiments.makespan.MakespanBaselineComparison` | `MakespanBaselineComparisonTest` | `output/makespan-baseline/results.json` |
| `org.workflowsim.experiments.energy.EnergyBaselineComparison` | `EnergyBaselineComparisonTest` | `output/energy-baseline/results.json` |

The tests run every cell of their study and check completion and metric invariants.

## Metrics

- Makespan is the simulation-end clock.
- Mean waiting is ready-to-start waiting of compute Jobs.
- Bounded slowdown is `max((wait + execution) / max(execution, τ), 1)` per compute Job, averaged;
  `τ` defaults to 10 s.
- Throughput is successful compute Jobs per simulated second.
- Energy includes every declared VM over the full makespan, including idle time; EDP is total
  energy × makespan.

Definitions and literature conventions are in the [metrics card](<../algorithms/METRICS_CARD.md>).

## Reading results

1. Makespan is not the only objective; also report waiting time, slowdown, throughput,
   utilization, energy, and cost, together with the workflow's structure.
2. Identical mappings produce identical metrics; inspect task-to-VM mappings before inferring
   algorithm equivalence.
3. A lower-power label does not imply lower energy: idle power over a longer makespan can
   outweigh active-power savings, so read energy, EDP, and makespan together.
4. `GREEN_HEFT` optimizes compute energy in its planner, not total fleet energy; planner
   objectives are not runtime totals.
5. Results hold for this declared platform only. A different VM count, tier table, storage rate,
   or DAG can change the ordering.
