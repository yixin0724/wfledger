# WfLedger

[![CI](https://github.com/yixin0724/wfledger/actions/workflows/ci.yml/badge.svg)](https://github.com/yixin0724/wfledger/actions/workflows/ci.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

WfLedger is a discrete-event simulator for scientific workflow scheduling research. You declare a
workflow, a platform and a set of algorithms; it runs them under the same model and reports
makespan, waiting time, cost, energy and carbon side by side.

It grew out of [WorkflowSim](https://github.com/WorkflowSim/WorkflowSim-1.0) and keeps its CloudSim 3
event kernel, but the scheduling algorithms, data movement models and metrics are rewritten
against their primary papers. The test suite runs about 2,300 cases, including schedules worked
out by hand from the original publications.

## Features

- **Algorithms from the literature**: HEFT, CPOP, PEFT, DLS, ETF, IC-PCP, MOHEFT, GreenHEFT,
  DVFS slack, EBPSM, CRCH, and the Min-Min / Max-Min / MCT / OLB / MET / Sufferage family, each
  documented with its source and any adaptation.
- **Data and network models**: shared storage, per-file replicas, endpoint and Fat-tree
  contention, bounded storage bandwidth and output write-back.
- **Energy, DVFS and carbon** under declared power models.
- **Failures** that interrupt a running task, with retry and checkpoint recovery.
- **Workloads**: Pegasus DAX, WfCommons WfFormat and WfInstances, plus a reproducible
  synthetic corpus of 360 instances across eight applications.
- **Statistics**: paired tests across workflow instances, multiple-comparison correction and
  effect sizes, with one decision layer per comparison.
- **Reproducibility**: optional per-run evidence that can be re-run and checked bit for bit.

## Quick start

Requires JDK 17+ and Maven 3.6.3+.

```bash
git clone https://github.com/yixin0724/wfledger.git
cd wfledger
make demo
```

Open `output/demo/index.html`. The demo compares three online schedulers on an Epigenomics
workflow; its configuration is
[experiments/configs/online-comparison.json](<experiments/configs/online-comparison.json>).

To run your own experiment, copy that file, change the workflow, platform or algorithms, and run:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run path/to/your-config.json output/my-study"
```

A run keeps only its summaries by default (`summary.json`, `summary.csv`, `report.html`). Add
`"evidence": "full"` to the configuration when a run must be re-runnable and independently
checked.

## Documentation

| I want to… | Read |
|---|---|
| Write an experiment configuration | [Workbench guide](<docs/getting-started/WORKBENCH.md>) |
| Drive experiments from Java | [Java API guide](<docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Know exactly what an algorithm or metric computes | [Algorithm contracts](<docs/algorithms/CONTRACTS.md>), [metrics card](<docs/algorithms/METRICS_CARD.md>) |
| Pick an algorithm and see what it supports | [Algorithm catalog](<docs/algorithms/CATALOG.md>) |
| Run a statistically sound comparison | [Standard study protocol](<experiments/studies/standard/PROTOCOL.md>) |
| Choose workflow inputs | [Datasets](<datasets/README.md>) |
| Build, test and contribute | [Build guide](<docs/getting-started/BUILD.md>), [contributing](<CONTRIBUTING.md>) |
| Browse everything | [Documentation index](<docs/README.md>) |

## Scope

WfLedger is meant for controlled, reproducible algorithm comparisons. It does not model packets,
TCP, switch queues, VM migration or real cloud prices, and its results are not calibrated against
a production cluster. The [algorithm catalog](<docs/algorithms/CATALOG.md>) lists which combinations
of algorithm, storage and network model are supported.

## Repository layout

| Path | Contents |
|---|---|
| `simulator/` | Simulation core, algorithms, models and metrics |
| `experiments/` | Workbench, experiment drivers, configurations and study protocols |
| `datasets/` | Workflow inputs |
| `scripts/` | Independent Python checkers and maintenance tools |
| `docs/` | Guides and contracts |

The Java package `org.workflowsim` and the Maven coordinates are kept from WorkflowSim for
compatibility.

## License and citation

GPL-3.0-or-later; see [LICENSE](LICENSE). WfLedger contains modified WorkflowSim and CloudSim 3
sources whose original notices are retained; see [NOTICE.md](NOTICE.md) and
[THIRD_PARTY.md](THIRD_PARTY.md).

If you use WfLedger in research, please cite it with [CITATION.cff](CITATION.cff) (GitHub's "Cite
this repository" button).
