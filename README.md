# WorkflowSim

[![CI](https://github.com/yixin0724/workflowsim-research/actions/workflows/ci.yml/badge.svg)](https://github.com/yixin0724/workflowsim-research/actions/workflows/ci.yml)
[![License: GPL-2.0-or-later](https://img.shields.io/badge/license-GPL--2.0--or--later-blue.svg)](LICENSE)

WorkflowSim is a discrete-event simulator for scientific workflow scheduling research. It models task dependencies, compute resources, storage, data transfer, and scheduling decisions in one declarative experiment, and produces replayable results with machine-checkable evidence.

The simulator supports controlled comparisons under declared conditions. Its results are not substitutes for production-cluster monitoring, real-network prediction, or cloud-provider billing.

## Quick start

Requires **JDK 17+ and Maven 3.6.3+**. From the repository root:

```bash
# Validate the example configuration
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# Run the experiment and generate the offline report
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

Open `index.html` in the output root to browse experiment records. Each experiment directory contains a `report.html` with scheme comparisons, task timelines, waiting time, and resource utilization. Completed results and failure causes are retained separately.

Generated files belong under `output/` and are not source inputs. Use `make clean-output` to remove regenerable working outputs when they are no longer needed.

## Choose an example

| Goal | Configuration |
|---|---|
| Compare online CPU scheduling policies | [Online scheduling comparison](<experiments/configs/online-comparison.json>) |
| Model network contention | [Network comparison](<experiments/configs/network-comparison.json>) |
| Inspect per-file sources, transfers, and CPU input gating | [Coherent file dataflow](<experiments/configs/coherent-file-dataflow-v2.json>) |
| Simulate bounded SOURCE storage and required output write-back | [Bounded storage dataflow](<experiments/configs/storage-dataflow-v3.json>) |
| Select a VM at control-ready time before transferring inputs | [Online target assignment](<experiments/configs/online-storage-dataflow-v1.json>) |

Examples have different support matrices. Do not mix algorithms, file systems, and network options arbitrarily; consult the [capability matrix](<docs/advanced/DATAFLOW_CAPABILITY_MATRIX.md>) and [algorithm catalog](<docs/algorithms/CATALOG.md>).

## Explore experiment results

| Topic | Report |
|---|---|
| HEFT, Min-Min, Max-Min, MCT, FCFS, and Round-Robin | [Traditional makespan comparison](<docs/experiments/MAKESPAN_BASELINE_COMPARISON.md>) |
| 1000 and 5000 task workflows | [Scale comparison](<docs/experiments/MAKESPAN_SCALE_COMPARISON.md>) |
| HEFT, GreenHEFT, MOHEFT, MIN_POWER, EDP selection, and DVFS slack | [Energy and EDP comparison](<docs/experiments/ENERGY_BASELINE_COMPARISON.md>) |
| Implemented literature algorithms and verification scope | [Paper reproductions](<docs/experiments/PAPER_REPRODUCTIONS.md>) |
| All reports, result files, reproduction commands, and evidence boundaries | [Experiment index](<docs/experiments/README.md>) |

These reports describe specific simulation scenes, not universal algorithm rankings. Always compare the workflow, VM platform, storage model, power model, and metric definitions before interpreting a result.

## Capability overview

| Area | Scope |
|---|---|
| Workflow inputs | Pegasus DAX XML and WfCommons WfFormat / WfInstances JSON; dependencies, work volume, and file declarations are read, while source-machine timelines are not replayed |
| Platform | Explicit Host/VM specifications, deterministic placement, capacity prechecks, declared costs, and optional power or frequency tiers |
| Scheduling and planning | Online CPU scheduling, independent-task static planning, controlled SHARED-storage and LOCAL DAG planning, plus labeled random and search baselines |
| Coherent file dataflow | Per-file sources, paths, merging, replica visibility, and CPU input barriers; optional endpoint or Fat-tree contention |
| Bounded storage | Bounded read/write/NIC bandwidths, SOURCE attachment, asynchronous output materialization, and terminal draining |
| Online target assignment | VM selection from submitted network observations and projected CPU reservations before input preparation |
| Experiment control | Staggered workflow arrivals, supported failure/retry combinations, deadline observation, and matrix-based computation cost |
| Custom policies | Registered scheduling and planning contracts with explicit model requirements and fail-fast validation |

The maintained runner uses SPACE_SHARED VMs and accepts NONE task clustering only. Simulations are serialized within a JVM; larger experiment matrices require separate processes. Online target assignment and online CPU scheduling are distinct decision phases.

## Execution pipeline and project layout

```text
workflow + SimulationConfig + PlatformProfile
    → input and combination validation → dependency parsing → optional static planning
    → dependency/arrival gating → fixed mapping or explicit online target assignment
    → input preparation → CPU dispatch and event loop → required-output draining
    → SimulationReport → evidence validation → offline report / exact replay
```

- `simulator/` — simulation core, platform, algorithms, network, policy interfaces, and tests.
- `experiments/` — Workbench, examples, experiment drivers, reports, and retained study protocols.
- `datasets/` — workflow inputs and [dataset notes](<datasets/README.md>).
- `scripts/` — independent evidence checks, browser acceptance, and performance comparison tools.
- `docs/` — user guides, model contracts, experiment reports, and maintenance notes.

The core module does not depend on the experiment module.

## Documentation and development

| Task | Guide |
|---|---|
| Run configurations and read reports | [Workbench guide](<docs/getting-started/WORKBENCH.md>) |
| Build experiments with the Java API | [Code-configuration guide](<docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Understand algorithm and metric definitions | [Algorithm contracts](<docs/algorithms/CONTRACTS.md>) and [metrics card](<docs/algorithms/METRICS_CARD.md>) |
| Build, test, and use tooling | [Build guide](<docs/getting-started/BUILD.md>) |
| Browse all documentation | [Documentation center](<docs/README.md>) |

Run the complete local acceptance gate before submitting changes:

```bash
make quality-gate
```

The gate covers unit and integration tests, coverage floors, core-API Javadoc, independent Python checks, documentation, language, and dataset integrity. Metric conventions, replay rules, and evidence scope are defined in the [reproducibility contract](<docs/experiments/REPRODUCIBILITY.md>) and [replay contract](<docs/experiments/RERUN_DIFF_CONTRACT.md>).

## License and citation

Released under the **GNU General Public License, version 2 or (at your option) any later version** ([LICENSE](LICENSE)). The simulator embeds the CloudSim 3 toolkit (`simulator/src/main/java/org/cloudbus/`), distributed under the GNU GPL by The University of Melbourne; its original notices are retained.

To cite this software, use [CITATION.cff](CITATION.cff). GitHub exposes the metadata through the "Cite this repository" button, and reference managers can import it directly.
