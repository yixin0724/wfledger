# P7 Compatible Reference Protocol

P7 is a frozen reference matrix of specific inputs, configurations, and online schedulers. This page defines the matrix, its fixed configuration, and how to run and validate it. It does not summarize the platform's capabilities.


## Frozen Matrix

The [matrix implementation](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineMatrix.java>) fixes **2 inputs × 2 platform variants × 5 schedulers = 20 run units** — not 20 inputs. Logical paths are relative to the absolute dataset root passed in; the input identities are fixed in `P7BaselineMatrix` and in [datasets/dax/INDEX.json](<../../../datasets/dax/INDEX.json>).

| Input | Logical path | Tasks | VMs |
|---|---|---:|---:|
| Epigenomics n100 | `dax/epigenomics/n100/Epigenomics_100.dax` | 100 | 10 |
| Epigenomics n997 | `dax/epigenomics/n997/Epigenomics_997.dax` | 997 | 50 |

The scheduler set is exactly `FCFS`, `READY_BATCH_ROUNDROBIN`, `READY_BATCH_MCT`, `READY_BATCH_MINMIN`, `READY_BATCH_MAXMIN`. The planning layer is `INVALID`; other algorithms, other workflow families, and WfCommons/WfInstances inputs do not join this matrix automatically.

| Platform variant | VM MIPS | Placement |
|---|---|---|
| H0 / `H0_HOMOGENEOUS` | all 1000 | one Host per VM |
| H1 / `H1_HETEROGENEOUS` | even VM IDs 500, odd 1500; equal counts, mean 1000 | one Host per VM |

Host/VM IDs are numbered consecutively from 0. Each Host has 2 PEs at 2000 MIPS each, 2048 MB RAM, bandwidth parameter 10000, and 1000000 MB of storage; each VM has 1 PE, 512 MB RAM, bandwidth parameter 1000, a 10000 MB image, Xen, and `SPACE_SHARED`. No explicit pin or per-VM pricing is set; the preflight and actual VM–Host assignments both enter the evidence and are checked. Shared storage uses the platform defaults of 1000000000000 MB capacity and a 15 MB/s transfer rate; the datacenter cost parameters are, in order, 3.0 per CPU-second, 0.05 for memory, 0.1 for storage, and 0.1 for bandwidth. These are abstract configuration values, not a calibration of hardware or of cloud bills.

## Fixed Configuration and the Meaning of Quantities

| Configuration | Fixed value |
|---|---|
| File system / data movement | `SHARED` / `LEGACY_WORKFLOWSIM_V1` |
| Clustering / overhead / failure | `NONE` / `OverheadModelConfig.none()` / disabled |
| Root seed | `20260901` |
| runtime reference MIPS / scale | `1000.0` / `1.0` |
| CloudSim minimum event interval | `0.1` simulated seconds |
| Arrival / deadline / cost matrix / topology | zero arrival for the single input / not requested (0) / none / none |
| Cost model | `DATACENTER`; recorded but not a P7 comparison metric |

A DAX's non-negative runtime is converted to `max(100, floor(runtimeSeconds * referenceMips * runtimeScale))` MI and then passes the current kernel's representability check. The event interval is the kernel's tick, not a physical link delay.

The primary metric `makespan` keeps its **simulation-end instant** meaning, stage-in included for this model; it must not be relabeled the same metric after being switched to logical task completion time. Secondary evidence includes Job success/failure, start/end/CPU times, categories, Task outcomes, and per-VM job summaries. A Job execution envelope may contain the stage-in expressed as integer MI; Task windows are model observations; VM summaries are not Host monitoring. Cost, utilization, energy, and the decision wall-clock time of the local machine are not P7 primary or secondary comparison metrics.

This matrix supports only descriptive comparison within a single family under a fixed model. Deterministic repetitions serve reproduction checks; they are not independent random samples, and no significance tests may be based on them. Identical root seeds do not mean event-keyed common random numbers. No cross-family superiority, real-trace reproduction, network/storage contention gains, failure robustness, or production-platform performance may be claimed on this basis.

## Running the Reference Matrix

Both commands run from the project root, compile the experiments module with its reactor
dependencies, and take explicit absolute paths. The first argument is the dataset root that
contains `dax/`; the second is an output directory that must be absent or empty.

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
  -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/p7-output"

mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
  -Dexec.args="/absolute/p7-output/p7-baseline-index.json"
```

The validator only reads. Maven compilation may still write build output.

## Execution, Index, and Validation

The P7 driver belongs to the experiments module of the default reactor build; the core module does not depend on it. The recommended path is the [executor](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineExecutor.java>) with an explicitly provided absolute dataset root and absolute empty output directory; the dataset root is the directory containing `dax/`, not the project root above it. A non-empty output directory is rejected. The CLI also supports a zero-argument mode that derives the dataset and a timestamped output directory from `user.dir`; that convenience must not replace explicitly recording the input/output locations of a study.

| Entry point | Index / identity |
|---|---|
| `executeBaseline(...)` or the CLI | `p7-baseline-index.json`; schema `workflowsim-p7-baseline-index-v3`; kind `FROZEN_FULL_BASELINE` |
| `executeSelection(...)` | `p7-reference-selection-index.json`; schema `workflowsim-p7-reference-selection-index-v1`; kind `PARTIAL_SELECTION_NOT_P7_BASELINE` |

Even if a selection run happens to cover every unit, the selection index is still not a formal baseline. The executor checks each run's input identity, Task count, and successful completion, and writes out and validates the evidence bundles; only after every run has succeeded does it write the index and validate it immediately.

New artifacts use manifest v4, metrics v2, events v1, and provenance v3, and record the current `WORK_CONSERVING_TASK_EXECUTION_V3` execution semantics (artifacts recording V2 are historical). New fields or an identical parameter matrix do not automatically turn old results into current-model results. The [index validator](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7EvidenceIndexValidator.java>) checks the complete 4×5 matrix, the input fingerprints, the fixed configuration, the platform and actual placements, the result summaries, the per-run artifacts, and the study identity; historical v2/v3 identities are read-only compatible, with missing fields not fabricated.

Before generating comparable results, one should pass the full gate that includes the integration tests. The matrix/executor tests, the core semantic tests, and the artifact checks jointly constrain this reference track; validating the index structure alone proves neither accuracy on a real platform nor that the historical numbers are unchanged under the current code. When retaining results, hand over the complete index and all referenced artifacts, not just the summary table.

## Protocol Identity

[P7ReferenceIdentity](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7ReferenceIdentity.java>) uses the study ID `p7-baseline`, the component identity `org.workflowsim:workflowsim-experiments:1.0` (a string the validator requires verbatim), the driver `org.workflowsim.experiments.reference.p7.P7BaselineExecutor`, and the logical ID `docs/experiments/reference-baselines/P7_PROTOCOL.md`. The digest of this file's actual bytes is written into the provenance of new runs; the file is located through the driver's code source, and a standalone JAR without the protocol records `available=false`. The older logical ID `P7_EXPERIMENT_PROTOCOL.md` is still accepted by the validator. Editing this file changes the protocol identity recorded by future runs.

General notes on the algorithms are in the [algorithm catalog](<../../algorithms/CATALOG.md>), and protocol management for new studies is described in the [studies directory](<../../../experiments/studies/README.md>).
