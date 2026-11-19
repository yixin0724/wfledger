# WorkflowSim Documentation Center

This center maintains the current usage guides, model contracts, and verification rules.
First-time users should read quick start → Workbench in order; consult the code-configuration
guide only when custom Java experiments are needed. Development rounds, completion lists,
and superseded audit drafts are not long-term entry points.

## Getting started

| Need | Document |
|---|---|
| Run an existing configuration and open its report | [Quick start](<getting-started/QUICK_START.md>) |
| Configure, validate, run, and rebuild offline reports | [Workbench guide](<getting-started/WORKBENCH.md>) |
| Build configurations, platforms, and experiments in Java | [Code-configuration guide](<getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Maven, Python, browser, and Javadoc checks | [Build and verification](<getting-started/BUILD.md>) |
| Configure IDEA modules, classpaths, and working directories | [IDEA setup](<getting-started/IDEA_SETUP.md>) |

## Choosing algorithms, inputs, and models

- [Algorithm catalog](<algorithms/CATALOG.md>): decision layers, available labels, combination limits, and label compatibility.
- [Algorithm and metrics contracts](<algorithms/CONTRACTS.md>): computation volume, communication, ordering, primary-paper correspondence, metric conventions, and verification boundaries.
- [Paper-metrics conventions card](<algorithms/METRICS_CARD.md>): per-metric literature-convention differences and citation advice.
- [Workflow inputs and datasets](<../datasets/README.md>): repository-bundled inputs, optional corpora, provenance, conversion, and format constraints.
- [Dataflow and network capability matrix](<advanced/DATAFLOW_CAPABILITY_MATRIX.md>): physical models, storage, online assignment, and unimplemented capabilities.
- [Energy dimension design](<advanced/ENERGY_DIMENSION_DESIGN.md>): the implemented and audited energy dimension, power-model architecture, metric surface, and declared scope.
- [Carbon dimension design](<advanced/CARBON_DIMENSION_DESIGN.md>): the two
  literature carbon accountings (intensity multiplier vs green-budget excess),
  the current architecture riding the audited energy chain, and the deliberate omissions.
- [DVFS dimension design](<advanced/DVFS_DIMENSION_DESIGN.md>): the third axis of
  the energy trilogy (measure → price → control) — per-tier frequency tables,
  the admission-assignment PE contract, the extended conservation law, and the
  three dispatch policies (DEFAULT / MIN_POWER / EDP_HEURISTIC).
- [Fat-tree topology and shared model](<advanced/FAT_TREE_DESIGN.md>): structure, placement, deterministic routing, and capacity-allocation boundaries.

## Dataflow and evidence reference

| Topic | Document |
|---|---|
| File identity, true sources, replica visibility, and the CPU input barrier | [Coherent file dataflow](<advanced/COHERENT_DATAFLOW_CONTRACT.md>) |
| Bounded SOURCE, post-commit reads, and required output write-back | [Storage dataflow](<advanced/STORAGE_DATAFLOW_CONTRACT.md>) |
| VM selection at control-ready, nominal scores, and action certification scope | [Online target assignment](<advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md>) |
| Exact fields and validation rules of the per-file sidecar | [File lifecycle format](<advanced/FILE_LIFECYCLE_FORMAT.md>) |
| Format, roles, and strict reading of the group network ledger | [Network ledger format](<advanced/NETWORK_LEDGER_FORMAT.md>) |
| Stable-allocation certificates, exact accounting, and truncated prefixes | [Ledger validation and metrics](<advanced/NETWORK_LEDGER_VALIDATION.md>) |
| Java-independent evidence verification and online check entry points | [Shared-core Python evidence verification](<advanced/NETWORK_LEDGER_PYTHON.md>) |
| Same verified snapshot, exact text, and bounded previews | [Offline network and action reports](<advanced/NETWORK_REPORT_DISPLAY.md>) |

## Experiments, replay, and statistics

- [Reproducibility contract](<experiments/REPRODUCIBILITY.md>): randomness, time/work, cost, reference values, and deadline semantics.
- [Campaign experiments and network studies](<experiments/CAMPAIGNS.md>): scenario matrices, seeds, parameter control, execution, and statistical methods.
- [Audit coverage ledger](<experiments/AUDIT_COVERAGE.md>): what has been verified per evidence
  dimension, which tampering yields which rerun verdict, mutation results, and the deliberate
  boundaries.
- [V&V case and golden-evidence ledger](<experiments/VV_CASE_LEDGER.md>): intended use, legal oracle
  classes, validation points, assessment states, residual risk, and the current baseline cases.
- [Fixed-commit reference implementation](<experiments/REFERENCE_IMPLEMENTATION.md>): the narrow
  CloudSim/WorkflowSim verification intersection, independent arithmetic, and explicit non-goals.
- [Workflow application structural benchmarks](<experiments/APPLICATION_BENCHMARKS.md>): classic DAX and
  Montage WfInstances verification, input populations, units, and validation boundaries.
- [Golden and reference-evidence registry](<experiments/GOLDEN_EVIDENCE_REGISTRY.md>): legal oracle classes,
  current anchors, golden change discipline, and performance evidence boundaries.
- [Experiment index](<experiments/README.md>): makespan, scale, energy, algorithm audits,
  result artifacts, reproduction commands, and evidence boundaries.
- [Paper algorithm reproductions](<experiments/PAPER_REPRODUCTIONS.md>): implemented literature algorithms,
  official artifacts, verification cases, and adaptation boundaries.
- [Cross-paper comparison scenes](<experiments/CROSS_PAPER_COMPARISON.md>): compatible SHARED/LOCAL scenes,
  real runner results, metric calibers, and comparison boundaries.
- [Algorithm and metric audit](<experiments/ALGORITHM_METRIC_AUDIT.md>): all reproduced algorithm families,
  corrected semantics, independent runtime-metric recomputation, and mutation coverage.
- [Algorithm experiment audit](<experiments/ALGORITHM_EXPERIMENT_AUDIT.md>): real DAG/resource/failure/seed
  matrix, invariants, metamorphic checks, and experiment-only defect fixes.
- [Traditional makespan baseline comparison](<experiments/MAKESPAN_BASELINE_COMPARISON.md>): HEFT,
  Min-Min, Max-Min, MCT, FCFS and Round-Robin on real scientific DAX workflows.
- [1000 and 5000 task makespan comparison](<experiments/MAKESPAN_SCALE_COMPARISON.md>): scale behavior,
  waiting time, slowdown, throughput and energy on Montage, CyberShake and Inspiral.
- [Energy and EDP baseline comparison](<experiments/ENERGY_BASELINE_COMPARISON.md>): HEFT, GreenHEFT,
  MOHEFT, MIN_POWER, EDP_HEURISTIC and DVFS slack on real scientific workflows.
- [Evidence replay and difference comparison](<experiments/RERUN_DIFF_CONTRACT.md>): configuration rebuilding, verdicts, exact core quantities, and the small set of volatile fields.
- [Independent study-audit tooling](<../scripts/STUDY_AUDIT.md>): the actual scope of general evidence/statistics checks, distinguished from dataflow-specific certificates.
- [FGCS study protocol](<../experiments/studies/fgcs/README.md>): external differential, sensitivity, energy, communication/storage ablation, and independent-JVM evidence-overhead matrices.

## Standard benchmark

- [Standard benchmark suite](<benchmark/STANDARD_BENCHMARK.md>): a frozen public comparison
  of the maintained algorithm corpus — 160 audited runs, each verifiable by a third party with
  one Python command; includes power and carbon variants and the statistical analysis
  [Energy Portrait of the Algorithm Corpus](<benchmark/ENERGY_PORTRAIT.md>).

## Research, positioning and quality

- [Science position statement](<SCIENCE-POSITION.md>): research identity, validity standard (internal validity), competitive positioning, and threats to validity.

## Maintenance

- [Contributing guide](<../CONTRIBUTING.md>): development setup, scientific constraints, verification, and pull-request expectations.
- [Security and data handling](<../SECURITY.md>): private vulnerability reporting, artifact metadata, and local-output precautions.
- [Documentation and source-comment conventions](<advanced/CODE_STYLE.md>): content layering, link/example requirements, source protection, and the document check command.
- [Mutation gate](<advanced/MUTATION_GATE.md>): registered-mutant zero-survival gate, killer-test ledger, and growth rules.
- [Dataflow state and performance measurement](<advanced/DATAFLOW_STATE_COPY_PERFORMANCE.md>): immutable history/active working set, measurement conventions, and repeatable comparisons.
- [Experiment module notes](<../experiments/README.md>): module responsibilities and maintenance entry point.
