# WfLedger Documentation

Start with the quick start and the Workbench guide. Consult the Java guide only for custom
experiments in code.

## Getting started

| Need | Document |
|---|---|
| Run an existing configuration and open its report | [Quick start](<getting-started/QUICK_START.md>) |
| Configure, validate, run, and rebuild offline reports | [Workbench guide](<getting-started/WORKBENCH.md>) |
| Build configurations, platforms, and experiments in Java | [Java API guide](<getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Maven, Python, browser, and Javadoc checks | [Build and verification](<getting-started/BUILD.md>) |
| IDEA modules, classpaths, and working directories | [IDEA setup](<getting-started/IDEA_SETUP.md>) |

## Algorithms, inputs, and models

| Topic | Document |
|---|---|
| Decision layers, labels, and combination limits | [Algorithm catalog](<algorithms/CATALOG.md>) |
| Computation, communication, ordering, paper correspondence, metric conventions | [Algorithm and metric contracts](<algorithms/CONTRACTS.md>) |
| Metric definitions versus literature conventions | [Metrics card](<algorithms/METRICS_CARD.md>) |
| Bundled inputs, optional corpora, provenance, and format rules | [Datasets](<../datasets/README.md>) |
| Physical data models, storage, online assignment, energy, carbon, DVFS scope | [Dataflow capability matrix](<advanced/DATAFLOW_CAPABILITY_MATRIX.md>) |
| Power model, energy metrics, conservation law | [Energy dimension](<advanced/ENERGY_DIMENSION_DESIGN.md>) |
| Intensity and green-budget carbon accounting | [Carbon dimension](<advanced/CARBON_DIMENSION_DESIGN.md>) |
| Frequency tiers, tier assignment, frequency policies | [DVFS dimension](<advanced/DVFS_DIMENSION_DESIGN.md>) |
| Fat-tree structure, placement, routing, capacity sharing | [Fat-tree topology](<advanced/FAT_TREE_DESIGN.md>) |

## Dataflow and evidence formats

| Topic | Document |
|---|---|
| File identity, sources, replica visibility, CPU input barrier, state structure | [Coherent file dataflow](<advanced/COHERENT_DATAFLOW_CONTRACT.md>) |
| Bounded SOURCE, post-commit reads, output write-back | [Storage dataflow](<advanced/STORAGE_DATAFLOW_CONTRACT.md>) |
| VM selection at control-ready, nominal scores, certification scope | [Online target assignment](<advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md>) |
| Per-file lifecycle sidecar fields and validation | [File lifecycle format](<advanced/FILE_LIFECYCLE_FORMAT.md>) |
| V1 group ledger format, validation, metrics, Python checker, report display | [Network ledger](<advanced/NETWORK_LEDGER.md>) |

## Experiments and verification

| Topic | Document |
|---|---|
| Overview of experiment documents | [Experiments](<experiments/README.md>) |
| Makespan, scale, and energy baseline configurations | [Baseline studies](<experiments/BASELINE_STUDIES.md>) |
| Scenario matrices, seeds, statistics, network studies, protocol identities | [Campaigns](<experiments/CAMPAIGNS.md>) |
| Randomness, units, cost, evidence formats, provenance | [Reproducibility](<experiments/REPRODUCIBILITY.md>) |
| Rerun, exact core comparison, verdicts | [Rerun and difference comparison](<experiments/RERUN_DIFF_CONTRACT.md>) |
| Oracle classes, verified anchors, tamper checks, audits, boundaries | [Verification and validation](<experiments/VERIFICATION.md>) |
| CloudSim/WorkflowSim fixed-commit oracle | [Reference implementation](<experiments/REFERENCE_IMPLEMENTATION.md>) |
| Implemented literature algorithms | [Paper reproductions](<experiments/PAPER_REPRODUCTIONS.md>) |
| Frozen online-scheduler reference matrix | [P7 reference protocol](<experiments/reference-baselines/P7_PROTOCOL.md>) |
| Fixed benchmark with power, carbon, and DVFS variants | [Standard benchmark](<benchmark/STANDARD_BENCHMARK.md>) |
| Independent study audit tooling | [Study audit](<../scripts/STUDY_AUDIT.md>) |
| Study protocols (standard, FGCS, workload corpus) | [Studies](<../experiments/studies/README.md>) |

## Positioning and maintenance

- [Science position](<SCIENCE-POSITION.md>): research identity, validity standard, positioning, threats to validity.
- [Contributing](<../CONTRIBUTING.md>): setup, scientific constraints, documentation and code style, verification.
- [Mutation gate](<advanced/MUTATION_GATE.md>): registered semantic canaries and their killer tests.
- [Security](<../SECURITY.md>): vulnerability reporting and data handling.
- [Third-party provenance](<../THIRD_PARTY.md>) and [notice](<../NOTICE.md>): source lineage and licenses.
- [Experiment module](<../experiments/README.md>): module responsibilities.
