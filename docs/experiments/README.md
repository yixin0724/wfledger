# Experiments

This directory documents how to run experiments, how results are verified, and how evidence is
compared across reruns. Read the [algorithm catalog](<../algorithms/CATALOG.md>) and the
[metrics card](<../algorithms/METRICS_CARD.md>) before comparing numbers.

| Document | Content |
|---|---|
| [Baseline studies](<BASELINE_STUDIES.md>) | Makespan, scale, and energy configurations on classic DAX workflows, and how to run them |
| [Campaigns](<CAMPAIGNS.md>) | Scenario matrices, seeds, randomization, execution, and statistical methods |
| [Reproducibility](<REPRODUCIBILITY.md>) | Randomness, time and work units, cost, evidence formats, and provenance |
| [Rerun and difference comparison](<RERUN_DIFF_CONTRACT.md>) | Configuration rebuilding, exact core comparison, verdicts, and volatile fields |
| [Verification and validation](<VERIFICATION.md>) | Legal oracle classes, verified anchors, evidence tamper checks, audits, and boundaries |
| [Reference implementation](<REFERENCE_IMPLEMENTATION.md>) | The fixed-commit CloudSim/WorkflowSim intersection used as an external oracle |
| [Paper reproductions](<PAPER_REPRODUCTIONS.md>) | Implemented literature algorithms, artifacts, and adaptation scope |
| [P7 reference protocol](<reference-baselines/P7_PROTOCOL.md>) | Frozen online-scheduler reference matrix |
| [Standard benchmark](<../benchmark/STANDARD_BENCHMARK.md>) | Fixed benchmark matrix with power, carbon, and DVFS variants |
| [Study protocols](<../../experiments/studies/README.md>) | Standard study, workload corpus, and network studies |

## Evidence levels

- **Contract verification**: a rule checked against a paper, an independent formula, or an
  execution invariant.
- **Controlled observation**: a result measured in one declared simulation scene; not a universal
  ranking or a real-platform measurement.
- **Regression evidence**: a frozen case that detects drift; it does not prove correctness.

## Event recording switch

Workbench JSON accepts the optional boolean `simulation.eventRecordingEnabled` (default `true`).
`false` disables events-v1 capture without changing execution: the run completes, its events
artifact is empty, and the manifest records `configuration.eventRecordingEnabled=false`. Workbench
rejects `false` together with an enabled `simulation.networkEvidence` object.

## Acceptance

`make quality-gate` runs Maven, coverage, the Python checks, documentation, language, and dataset
checks. The registered mutation gate runs separately and must not run concurrently with other
builds; see the [mutation gate](<../advanced/MUTATION_GATE.md>).
