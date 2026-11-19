# Experiment Index

This is the maintained entry point for experiment reports, result artifacts, and their evidence
boundaries. Read the [algorithm catalog](<../algorithms/CATALOG.md>) and
[metrics conventions card](<../algorithms/METRICS_CARD.md>) before comparing numbers.

## Evidence levels

- **Contract verification**: a rule is checked against a paper, an independent formula, or an
  execution invariant. See [algorithm and metric audit](<ALGORITHM_METRIC_AUDIT.md>) and
  [paper reproductions](<PAPER_REPRODUCTIONS.md>).
- **Controlled experiment observation**: a result measured in one declared simulation scene. It is
  not a universal ranking or a real-platform measurement.
- **Regression evidence**: a frozen case used to detect drift. It does not prove that the underlying
  algorithm is correct; consult the [golden-evidence registry](<GOLDEN_EVIDENCE_REGISTRY.md>).

## Makespan experiments

| Report | Inputs and runs | Main observation | Boundary |
|---|---|---|---|
| [Traditional makespan baselines](<MAKESPAN_BASELINE_COMPARISON.md>) | 7 real DAX datasets × 6 algorithms = 42 runs | HEFT best on 5/7; MCT best on CyberShake 50; Max-Min best on CyberShake 100; Min-Min lowest mean wait on all 7 | One four-VM SHARED scene; not a universal ranking |
| [1000 and 5000 task makespan](<MAKESPAN_SCALE_COMPARISON.md>) | Montage, CyberShake, Inspiral at 1000 and 5000 tasks = 36 runs | HEFT best on all three datasets at both scales; Min-Min lowest wait; throughput approximately stable | 5000 is five independent copies of each 1000-task DAG, not a new real trace |

The complete result tables are included in these reports. Raw JSON results are generated locally under
`output/makespan-baseline/` and `output/makespan-scale/`; generated evidence is intentionally not published
in the source repository. Use the commands below to reproduce the full result files.

## Energy experiments

| Report | Inputs and runs | Main observation | Boundary |
|---|---|---|---|
| [Energy and EDP baselines](<ENERGY_BASELINE_COMPARISON.md>) | 5 real DAX datasets × 7 policies = 35 runs | MIN_POWER has lowest energy/EDP but usually much higher makespan; DVFS slack often gives a better tradeoff; GreenHEFT is not guaranteed to reduce total fleet energy | Flat and tiered power groups are separate; planner energy is not runtime total energy |

The energy report contains the full comparison table. Raw results are generated locally under
`output/energy-baseline/` and are intentionally not published in the source repository.

Workbench outputs are generated under `output/workbench-makespan/`, `output/workbench-scale/`, and
`output/workbench-energy/`. Each contains the standard configuration, per-run evidence, detailed report,
and unified HTML/JSON/CSV summaries.

## Algorithm and execution audits

| Report | Coverage | Purpose |
|---|---|---|
| [Algorithm and metric audit](<ALGORITHM_METRIC_AUDIT.md>) | All reproduced algorithm families and metric formulas | Links decision semantics, adapters, task mapping, runtime execution and independent metric recomputation |
| [Algorithm experiment audit](<ALGORITHM_EXPERIMENT_AUDIT.md>) | 82 matrix runs plus special/metamorphic cases | Uses DAG/resource/failure/seed experiments to find defects that static review misses |
| [Cross-paper comparison](<CROSS_PAPER_COMPARISON.md>) | Compatible SHARED and LOCAL scenes | Shows why identical mappings produce identical metrics and why planner/runtime costs must be separated |
| [Paper reproductions](<PAPER_REPRODUCTIONS.md>) | CaWoSched, CWS, NACO, Reshi, GreenHEFT, MOHEFT, IC-PCP, Network-Aware HEFT, EBPSM, DVFS, CRCH and DHEFT | Primary-paper/reference-artifact/implementation/verification chain and explicit adaptation scope |
| [Audit coverage](<AUDIT_COVERAGE.md>) | Registered semantic and mutation axes | Explains which defects are guarded by current tests and which claims are not made |
| [V&V case ledger](<VV_CASE_LEDGER.md>) | Verification, validation and regression cases | Separates verified behavior from experimental observations and historical evidence |
| [Golden evidence registry](<GOLDEN_EVIDENCE_REGISTRY.md>) | Paper, contract, invariant and regression evidence | Identifies the legal source of each expected value |

## Reproduction commands

Run from the repository root with JDK 17+ and Maven 3.6.3+:

```bash
# Static HEFT and online makespan comparisons (7 + 35 runs)
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/makespan-static-baseline.json output/makespan-static-study"
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/makespan-online-baseline.json output/makespan-online-study"

# Static HEFT and online scale comparisons (6 + 30 runs)
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/makespan-static-scale.json output/makespan-static-scale-study"
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/makespan-online-scale.json output/makespan-online-scale-study"

# Energy-aware planners with flat power (15 runs)
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/energy-flat.json output/energy-flat-study"

# Frequency policies and DVFS slack with tiered power (20 runs)
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/energy-tiered.json output/energy-tiered-study"

# Algorithm and metric audits
mvn -pl :workflowsim-workbench -am -Dtest=AlgorithmMetricAuditTest test
mvn -pl :workflowsim-workbench -am -Dtest=AlgorithmExperimentAuditTest test
mvn -pl :workflowsim-workbench -am -Dtest=CrossPaperComparisonTest test
```

Each generated experiment directory contains `summary.html`, `summary.json`, and `summary.csv`,
plus the detailed `report.html` and the per-run evidence packages. The summaries include workflow,
algorithm, waiting time, slowdown, makespan, energy, EDP, cost, and completion counts. Static HEFT and
online ready-job scheduling deliberately use separate configurations because they are different decision layers.
The generated 113 runs were cross-checked against the corresponding independent drivers with zero differences in
makespan, waiting time, true slowdown, energy, or modeled processing cost.

The complete acceptance command is `make quality-gate`; it includes Maven, coverage, independent
Python checks, documentation, language and dataset checks. A separate mutation run is required for
semantic canaries and must not run concurrently with other builds.

## Workbench event recording

Standard Workbench JSON accepts the optional literal boolean `simulation.eventRecordingEnabled`, default `true`.
Setting it to `false` disables events-v1 capture without changing workflow execution: the run still completes, its
events artifact is empty, and the manifest explicitly records `configuration.eventRecordingEnabled=false`. This switch
never silently discards requested lifecycle evidence. Workbench rejects `eventRecordingEnabled=false` together with an
enabled `simulation.networkEvidence` object during preflight; omit that object or keep event recording enabled. All
existing defaults and physical/model combination checks continue to apply.

## FGCS study

The [FGCS study protocol](../../experiments/studies/fgcs/README.md) defines the complete differential, sensitivity, energy, communication/storage ablation, and evidence-overhead matrices. Its generated outputs remain in `paper/tmp/`; the protocol and compact differential verifier are source-controlled. Unique-producer-derived lifecycle inputs are explicitly separated from raw DAX inputs.

## Reading rules and current limitations

1. **Makespan is not the only scheduling objective.** Also report waiting time, true slowdown,
   throughput, utilization, energy, cost and the workflow's dependency/parallelism structure.
2. **Do not mix physical models.** SHARED and LOCAL, flat and tiered power, DATACENTER and VM cost,
   and planner versus runtime objectives are different calibers.
3. **Do not interpret equal metrics as algorithm equivalence.** Identical mappings produce identical
   makespan, energy and cost; inspect task-to-VM mappings before drawing conclusions.
4. **Scale results are model-specific.** The 5000-task case uses independent copies; it does not
   validate a deeper original DAG or a larger real cluster.
5. **Energy is not automatically lower with a greener label.** Idle power over a longer makespan can
   outweigh a lower active-power choice. Always inspect EDP and makespan together.
6. **No universal claim is made** about exact paper tables, full corpus reproduction, global
   optimality, complete Pareto coverage, production performance or real-cluster calibration.
