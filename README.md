# WfLedger

[![CI](https://github.com/yixin0724/workflowsim-research/actions/workflows/ci.yml/badge.svg)](https://github.com/yixin0724/workflowsim-research/actions/workflows/ci.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

WfLedger is a discrete-event simulator for scientific workflow scheduling research. It models task dependencies, compute resources, storage, data transfer, and scheduling decisions in one declarative experiment, and produces replayable results with machine-checkable evidence.

It is intended for reproducible algorithm studies, not for production-cluster monitoring, real-network prediction, or cloud-provider billing.

## Name and lineage

WfLedger means **workflow + evidence ledger**. The platform originated from WorkflowSim and retains a maintained CloudSim 3 event kernel, but is independently developed around explicit execution contracts, independently auditable evidence, and exact replay. The `org.workflowsim` Java namespace and Maven coordinates remain compatibility identifiers; they are not the product name. Ledger denotes an auditable record, not a blockchain or a cryptographic immutability claim.

## What you can do

- Compare static DAG planners and online ready-job schedulers on real scientific workflows.
- Model endpoint and Fat-tree flow contention, file replicas, input visibility, bounded storage, and output write-back.
- Evaluate makespan, waiting time, slowdown, utilization, cost, energy, EDP, carbon, and DVFS policies under declared models.
- Rebuild experiments and independently validate manifests, metrics, event streams, file lifecycles, and energy accounting.

## Quick start

Requires **JDK 17+ and Maven 3.6.3+**.

```bash
git clone https://github.com/yixin0724/workflowsim-research.git
cd workflowsim-research

# Validate the example configuration
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# Run the experiment and generate offline reports
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

Open **`output/workbench/index.html`** to browse experiment records. Each experiment directory contains:

- `report.html` — detailed schedules and timelines;
- `summary.html` / `summary.json` / `summary.csv` — algorithm and metric comparisons.

By default a run keeps only these summaries. Add `"evidence": "full"` to the configuration to also write a
per-run manifest, metrics file, event stream and sidecar; that bundle is what rerun verification and the
standalone report need, and it is what makes experiment output large.

Generated files belong under `output/` and are not source inputs. Use `make clean-output` to remove regenerable working outputs.

## Choose an experiment

| Goal | Configuration |
|---|---|
| Compare online CPU scheduling policies | [Online scheduling comparison](<experiments/configs/online-comparison.json>) |
| Compare static HEFT across seven scientific workflows | [Static makespan baseline](<experiments/configs/makespan-static-baseline.json>) |
| Compare five online makespan algorithms across seven workflows | [Online makespan baseline](<experiments/configs/makespan-online-baseline.json>) |
| Compare static and online algorithms at 1000 and 5000 tasks | [Static scale study](<experiments/configs/makespan-static-scale.json>), [online scale study](<experiments/configs/makespan-online-scale.json>) |
| Compare energy-aware planners with flat power | [Flat-power energy study](<experiments/configs/energy-flat.json>) |
| Compare frequency policies and DVFS slack | [Tiered-power energy study](<experiments/configs/energy-tiered.json>) |
| Model network contention | [Network comparison](<experiments/configs/network-comparison.json>) |
| Inspect per-file sources, transfers, and CPU input gating | [Coherent file dataflow](<experiments/configs/coherent-file-dataflow-v2.json>) |
| Simulate bounded SOURCE storage and required output write-back | [Bounded storage dataflow](<experiments/configs/storage-dataflow-v3.json>) |
| Select a VM at control-ready time before transferring inputs | [Online target assignment](<experiments/configs/online-storage-dataflow-v1.json>) |

Examples have different support matrices. Do not mix algorithms, file systems, and network options arbitrarily; consult the [capability matrix](<docs/advanced/DATAFLOW_CAPABILITY_MATRIX.md>) and [algorithm catalog](<docs/algorithms/CATALOG.md>).

## Simulation scope

| Area | Supported model |
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

**Not included:** packet-level TCP, packet loss or retransmission, switch queues, ECMP or adaptive routing, link propagation delay, VM migration, CPU oversubscription, hardware monitoring, or calibrated cloud prices. Planner objectives and runtime measurements are separate calibers.

## Execution pipeline

```text
workflow + SimulationConfig + PlatformProfile
    → input and combination validation → dependency parsing → optional static planning
    → dependency/arrival gating → fixed mapping or explicit online target assignment
    → input preparation → CPU dispatch and event loop → required-output draining
    → SimulationReport → evidence validation → offline report / exact replay
```

## Experiment reports

| Topic | Report |
|---|---|
| Makespan, scale, and energy baselines on classic DAX workflows | [Baseline studies](<docs/experiments/BASELINE_STUDIES.md>) |
| Implemented literature algorithms and verification scope | [Paper reproductions](<docs/experiments/PAPER_REPRODUCTIONS.md>) |
| Experiment guides, verification, and rerun contracts | [Experiment index](<docs/experiments/README.md>) |

These reports describe specific simulation scenes, not universal algorithm rankings. Always compare the workflow, VM platform, storage model, power model, and metric definitions before interpreting a result.

## Repository layout

- `simulator/` — simulation core, platform, algorithms, network, policy interfaces, and tests.
- `experiments/` — Workbench, examples, experiment drivers, reports, and retained study protocols.
- `datasets/` — workflow inputs and [dataset notes](<datasets/README.md>).
- `scripts/` — independent evidence checks, browser acceptance, and performance comparison tools.
- `docs/` — user guides, model contracts, experiment reports, and maintenance notes.

The core module does not depend on the experiment module.

## Documentation and contributing

| Task | Guide |
|---|---|
| Run configurations and read reports | [Workbench guide](<docs/getting-started/WORKBENCH.md>) |
| Build experiments with the Java API | [Code-configuration guide](<docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Understand algorithm and metric definitions | [Algorithm contracts](<docs/algorithms/CONTRACTS.md>) and [metrics card](<docs/algorithms/METRICS_CARD.md>) |
| Build, test, and use tooling | [Build guide](<docs/getting-started/BUILD.md>) |
| Browse all documentation | [Documentation center](<docs/README.md>) |
| Propose changes or report an issue | [Contributing guide](<CONTRIBUTING.md>) |
| Review vulnerability reporting and data-handling precautions | [Security guide](<SECURITY.md>) |

Run the complete local acceptance gate before submitting changes:

```bash
make quality-gate
```

The gate covers unit and integration tests, coverage floors, core-API Javadoc, independent Python checks, documentation, language, and dataset integrity. Metric conventions, replay rules, and evidence scope are defined in the [reproducibility contract](<docs/experiments/REPRODUCIBILITY.md>) and [replay contract](<docs/experiments/RERUN_DIFF_CONTRACT.md>).

## License and citation

Released under the **GNU General Public License, version 3 or (at your option) any later version** ([LICENSE](LICENSE)). The simulator embeds modified WorkflowSim and CloudSim 3 source files; their original copyright and license notices are retained in the corresponding sources.

Third-party provenance, modification notices, and bundled license texts are documented in [NOTICE.md](NOTICE.md), [THIRD_PARTY.md](THIRD_PARTY.md), and [licenses/README.md](licenses/README.md). The `org.workflowsim` Java namespace and Maven coordinates are compatibility identifiers, not upstream endorsements.

To cite this software, use [CITATION.cff](CITATION.cff). GitHub exposes the metadata through the "Cite this repository" button, and reference managers can import it directly.
